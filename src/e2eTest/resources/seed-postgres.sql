--
-- Copyright 2026 Chet Mancini
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

CREATE TABLE items (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    created TIMESTAMP NOT NULL,
    note TEXT
);

INSERT INTO items (id, name, created, note) VALUES
    (1, 'alpha', '2024-01-02 03:04:05', NULL),
    (2, 'say "hi", ok
bye', '2024-06-07 08:09:10', 'second');

CREATE TABLE big AS SELECT g AS n FROM generate_series(1, 10000) AS g;
