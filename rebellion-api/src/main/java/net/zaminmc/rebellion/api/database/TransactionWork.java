package net.zaminmc.rebellion.api.database;

import java.sql.SQLException;

/**
 * Unit of work executed inside a transaction. Throwing {@link SQLException} or
 * any {@link RuntimeException} rolls the transaction back; the exception is
 * propagated to the caller's future.
 */
@FunctionalInterface
public interface TransactionWork<T> {

    T apply(TransactionContext tx) throws SQLException;
}
