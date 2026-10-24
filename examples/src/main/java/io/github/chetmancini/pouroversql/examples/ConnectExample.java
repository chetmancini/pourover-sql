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
package io.github.chetmancini.pouroversql.examples;

import io.github.chetmancini.pouroversql.ConnectionParams;
import io.github.chetmancini.pouroversql.Pourover;
import io.github.chetmancini.pouroversql.clients.SqlEngine;

import java.util.List;
import java.util.Map;

/**
 * Shows how to connect to a real database. It compiles with the build but is never run, because it
 * needs a PostgreSQL server; the other examples run against in-memory H2.
 */
public final class ConnectExample {

    private ConnectExample() {
    }

    /**
     * Lists the users of a PostgreSQL database on localhost.
     *
     * @param password the password for the {@code app} user
     * @return the rows of the {@code users} table
     */
    static List<Map<String, Object>> listUsers(String password) {
        // snippet:start connect
        Pourover db = Pourover.connect(SqlEngine.POSTGRESQL,
                new ConnectionParams("localhost", 5432, "app", password, "main"));
        List<Map<String, Object>> users = db.list("SELECT * FROM users");
        // snippet:end connect
        return users;
    }
}
