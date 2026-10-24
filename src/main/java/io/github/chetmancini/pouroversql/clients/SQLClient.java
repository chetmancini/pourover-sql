/*
Copyright 2019-present, Simon Data, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at:
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package io.github.chetmancini.pouroversql.clients;

import javax.sql.DataSource;

/**
 * SQLClient
 * Builds a {@link DataSource} for one database engine from {@link io.github.chetmancini.pouroversql.ConnectionParams}.
 * Query execution lives in {@link io.github.chetmancini.pouroversql.Pourover}.
 */
public interface SQLClient {

    /**
     * Creates a DataSource for this engine. Connections are opened lazily by the caller.
     * @return a new DataSource
     */
    DataSource dataSource();
}
