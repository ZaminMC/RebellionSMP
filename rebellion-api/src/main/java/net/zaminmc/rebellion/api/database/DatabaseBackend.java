package net.zaminmc.rebellion.api.database;

/** Database backend in use. Each backend has documented connection semantics. */
public enum DatabaseBackend {
    /** Embedded, zero-setup, one writer connection. Default. */
    SQLITE,
    /** External MySQL server using the server-bundled MySQL Connector/J. */
    MYSQL
}
