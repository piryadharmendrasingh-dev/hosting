package com.example.rankhomes.storage;

import com.example.rankhomes.RankHomes;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite persistence. One connection, used only from a single worker thread,
 * so every statement is serialised and writes can never race with reads.
 */
public final class HomeStorage {

    @FunctionalInterface
    private interface SqlCall<T> {
        T run() throws SQLException;
    }

    private final RankHomes plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RankHomes-SQLite");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public HomeStorage(RankHomes plugin) {
        this.plugin = plugin;
    }

    /** Opens the database and creates tables. Called once from onEnable. */
    public void open() throws SQLException {
        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new SQLException("Could not create plugin data folder " + dir);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC driver (org.sqlite.JDBC) not found on this server", ex);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "homes.db").getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS homes ("
                    + "uuid TEXT NOT NULL, name TEXT NOT NULL, world TEXT NOT NULL, world_uid TEXT, "
                    + "x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, "
                    + "created_at INTEGER NOT NULL, PRIMARY KEY (uuid, name))");
            st.execute("CREATE TABLE IF NOT EXISTS player_limits ("
                    + "uuid TEXT PRIMARY KEY, home_limit INTEGER NOT NULL)");
        }
        migrateLegacyYaml(dir);
    }

    // ------------------------------------------------------------------ reads

    public CompletableFuture<PlayerData> loadPlayer(UUID uuid) {
        return supply(() -> {
            PlayerData data = new PlayerData();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name, world, world_uid, x, y, z, yaw, pitch, created_at FROM homes WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Home home = new Home(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                                (float) rs.getDouble(7), (float) rs.getDouble(8), rs.getLong(9));
                        if (home.hasValidCoordinates()) {
                            data.homes().put(home.name(), home);
                        } else {
                            plugin.getLogger().warning("Skipping home '" + home.name() + "' of " + uuid + ": invalid coordinates.");
                        }
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT home_limit FROM player_limits WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        data.limitOverride(rs.getInt(1));
                    }
                }
            }
            return data;
        });
    }

    // ----------------------------------------------------------------- writes

    public void saveHome(UUID uuid, Home h) {
        write("save home " + h.name(), () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO homes (uuid, name, world, world_uid, x, y, z, yaw, pitch, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(uuid, name) DO UPDATE SET world=excluded.world, world_uid=excluded.world_uid, "
                            + "x=excluded.x, y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, h.name());
                ps.setString(3, h.worldName());
                ps.setString(4, h.worldUid());
                ps.setDouble(5, h.x());
                ps.setDouble(6, h.y());
                ps.setDouble(7, h.z());
                ps.setDouble(8, h.yaw());
                ps.setDouble(9, h.pitch());
                ps.setLong(10, h.createdAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteHome(UUID uuid, String name) {
        write("delete home " + name, () -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Stores an admin limit override; {@code null} removes it. */
    public void setLimitOverride(UUID uuid, Integer limit) {
        write("set limit override", () -> {
            if (limit == null) {
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM player_limits WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO player_limits (uuid, home_limit) VALUES (?, ?) "
                                + "ON CONFLICT(uuid) DO UPDATE SET home_limit = excluded.home_limit")) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, limit);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // -------------------------------------------------------------- lifecycle

    /** Waits for queued writes, then closes the connection. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out waiting for pending database writes.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Error closing database", ex);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private <T> CompletableFuture<T> supply(SqlCall<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(call.run());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    private void write(String description, SqlCall<Void> call) {
        supply(call).exceptionally(t -> {
            plugin.getLogger().log(Level.SEVERE, "Database error while trying to " + description, t);
            return null;
        });
    }

    /** One-time import of the old homes.yml format used by RankHomes 1.0.0. */
    private void migrateLegacyYaml(File dir) throws SQLException {
        File legacy = new File(dir, "homes.yml");
        if (!legacy.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacy);
        int imported = 0;
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO homes (uuid, name, world, world_uid, x, y, z, yaw, pitch, created_at) "
                        + "VALUES (?,?,?,NULL,?,?,?,?,?,?)")) {
            for (String key : yaml.getKeys(false)) {
                UUID owner;
                try {
                    owner = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                ConfigurationSection homes = yaml.getConfigurationSection(key);
                if (homes == null) {
                    continue;
                }
                for (String name : homes.getKeys(false)) {
                    ConfigurationSection h = homes.getConfigurationSection(name);
                    if (h == null || h.getString("world") == null) {
                        continue;
                    }
                    ps.setString(1, owner.toString());
                    ps.setString(2, name.toLowerCase(Locale.ROOT));
                    ps.setString(3, h.getString("world"));
                    ps.setDouble(4, h.getDouble("x"));
                    ps.setDouble(5, h.getDouble("y"));
                    ps.setDouble(6, h.getDouble("z"));
                    ps.setDouble(7, h.getDouble("yaw"));
                    ps.setDouble(8, h.getDouble("pitch"));
                    ps.setLong(9, System.currentTimeMillis());
                    ps.addBatch();
                    imported++;
                }
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
        File renamed = new File(dir, "homes.yml.migrated");
        if (legacy.renameTo(renamed)) {
            plugin.getLogger().info("Imported " + imported + " homes from homes.yml (renamed to homes.yml.migrated).");
        } else {
            plugin.getLogger().warning("Imported homes from homes.yml but could not rename it; delete it manually.");
        }
    }
}
