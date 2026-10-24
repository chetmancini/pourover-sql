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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SqlEngineTest {

    @Test
    public void testByName() throws Exception {
        assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("mssql"));
        assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("sqlserver"));
        assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("sqlServer"));
    }

    @Test
    public void testEachName() {
        assertEquals(SqlEngine.ATHENA, SqlEngine.byName("athena"));
        assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("sqlserver"));
        assertEquals(SqlEngine.MYSQL, SqlEngine.byName("mysql"));
        assertEquals(SqlEngine.POSTGRESQL, SqlEngine.byName("postgresql"));
        assertEquals(SqlEngine.ORACLE, SqlEngine.byName("oracle"));
        assertEquals(SqlEngine.INFORMIX, SqlEngine.byName("informix"));
        assertEquals(SqlEngine.REDSHIFT, SqlEngine.byName("redshift"));
        assertEquals(SqlEngine.BIGQUERY, SqlEngine.byName("bigquery"));
        assertEquals(SqlEngine.SNOWFLAKE, SqlEngine.byName("snowflake"));
    }

    @Test
    public void testByNameIgnoresSpaces() {
        assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("ms sql"));
        assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("SQL Server"));
        assertEquals(SqlEngine.ORACLE, SqlEngine.byName("oracle db"));
        assertEquals(SqlEngine.INFORMIX, SqlEngine.byName("ibm informix"));
    }

    @Test
    public void engineLookupIsLocaleIndependent() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            // Turkish upper-cases 'i' to a dotted capital I, which broke "informix".
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals(SqlEngine.INFORMIX, SqlEngine.byName("informix"));
            assertEquals(SqlEngine.SQLSERVER, SqlEngine.byName("microsoft sql server"));
            assertEquals(SqlEngine.ATHENA, SqlEngine.byName("aws athena"));
            assertEquals(SqlEngine.BIGQUERY, SqlEngine.byName("bigquery"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
