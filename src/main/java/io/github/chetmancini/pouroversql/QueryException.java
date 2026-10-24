/*
 * Copyright 2026 Chet Mancini
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.chetmancini.pouroversql;

import java.io.Serial;
import java.sql.SQLException;

/**
 * Unchecked wrapper for a {@link SQLException} raised while connecting, querying or reading rows.
 *
 * <p>The message never contains the connection password: when Pourover knows the password
 * (via {@link Pourover#connect}) it is replaced with {@code [REDACTED]} in this message and, if the
 * driver echoed it, the cause is replaced by a scrubbed copy carrying the same SQL state, vendor
 * code and stack trace.
 */
public class QueryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String REDACTED = "[REDACTED]";

    private final String sqlState;

    /**
     * Creates an exception for a failed JDBC operation.
     * @param message a description that must not contain credentials
     * @param cause the underlying JDBC error
     */
    public QueryException(String message, SQLException cause) {
        super(message, cause);
        this.sqlState = cause == null ? null : cause.getSQLState();
    }

    /**
     * The SQL state reported by the driver, if any.
     * @return the five-character SQLSTATE, or null
     */
    public String getSqlState() {
        return sqlState;
    }

    /**
     * Wraps a JDBC error, removing {@code secret} from the message and cause.
     * @param e the JDBC error
     * @param secret the password to scrub, or null when unknown
     * @return the wrapping exception
     */
    static QueryException wrap(SQLException e, String secret) {
        SQLException cause = e;
        String driverMessage = e.getMessage() == null ? "" : e.getMessage();
        if (secret != null && !secret.isEmpty() && driverMessage.contains(secret)) {
            driverMessage = driverMessage.replace(secret, REDACTED);
            SQLException scrubbed = new SQLException(driverMessage, e.getSQLState(), e.getErrorCode());
            scrubbed.setStackTrace(e.getStackTrace());
            cause = scrubbed;
        }
        StringBuilder message = new StringBuilder("Query failed");
        if (e.getSQLState() != null) {
            message.append(" [SQLState ").append(e.getSQLState()).append(']');
        }
        message.append(": ").append(driverMessage);
        return new QueryException(message.toString(), cause);
    }
}
