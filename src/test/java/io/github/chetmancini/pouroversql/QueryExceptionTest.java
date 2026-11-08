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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExceptionTest {

    @Test
    void topLevelScrubbingRetainsSqlDiagnosticsAndStackTrace() {
        String secret = secret();
        SQLException original = new SQLException("login failed: " + secret, "28000", 18456);
        StackTraceElement[] stack = {frame("Driver.connect", 42)};
        original.setStackTrace(stack);

        QueryException wrapped = wrap(original, params(secret));

        assertThat(wrapped.getMessage()).isEqualTo("Query failed [SQLState 28000]: login failed: [REDACTED]");
        assertThat(wrapped.getSqlState()).isEqualTo("28000");
        SQLException cause = (SQLException) wrapped.getCause();
        assertThat((Throwable) cause).isNotSameAs(original);
        assertThat(cause.getMessage()).isEqualTo("login failed: [REDACTED]");
        assertThat(cause.getSQLState()).isEqualTo("28000");
        assertThat(cause.getErrorCode()).isEqualTo(18456);
        assertThat(cause.getStackTrace()).containsExactly(stack);
    }

    @Test
    void customPropertyCredentialsAreScrubbedFromOuterAndNestedMessages() {
        String passphrase = secret();
        String credential = secret();
        Properties properties = new Properties();
        properties.setProperty("KeyPassPhrase", passphrase);
        properties.setProperty("ClientCredential", credential);
        SQLException original = new SQLException("driver echoed " + passphrase, "28000");
        original.initCause(new IllegalStateException("nested " + credential));

        QueryException wrapped = wrap(original, params(null, null, properties));

        assertThat(wrapped.getMessage()).doesNotContain(passphrase, credential).contains("[REDACTED]");
        assertThat(wrapped.getCause().getMessage()).doesNotContain(passphrase);
        Throwable nested = wrapped.getCause().getCause();
        assertThat(nested).isExactlyInstanceOf(Throwable.class);
        assertThat(nested.getMessage()).isEqualTo("nested [REDACTED]");
    }

    @Test
    void credentialsFromInitialQueryAndSemicolonJdbcUrlsAreScrubbed() {
        String initialSecret = secret();
        String querySecret = secret();
        String semicolonSecret = secret();
        String initialUrl = "jdbc:awsathena://AuthToken=" + initialSecret + ";Workgroup=primary";
        String queryUrl = "jdbc:athena://query.example?Workgroup=primary&AwsCredential=" + querySecret
                + "&Region=us-east-1";
        String semicolonUrl = "jdbc:athena://semicolon.example;Workgroup=secondary;AuthToken="
                + semicolonSecret + ";Region=us-west-2";

        QueryException initial = wrap(
                new SQLException("driver echoed " + initialUrl), params(initialUrl, null, null));
        QueryException query = wrap(new SQLException("driver echoed " + queryUrl), params(queryUrl, null, null));
        QueryException semicolon = wrap(
                new SQLException("driver echoed " + semicolonUrl), params(semicolonUrl, null, null));

        assertThat(initial.getMessage()).doesNotContain(initialSecret)
                .contains("Workgroup=primary", "AuthToken=[REDACTED]");
        assertThat(initial.getCause().getMessage()).doesNotContain(initialSecret);
        assertThat(query.getMessage()).doesNotContain(querySecret)
                .contains("Workgroup=primary", "Region=us-east-1", "AwsCredential=[REDACTED]");
        assertThat(query.getCause().getMessage()).doesNotContain(querySecret);
        assertThat(semicolon.getMessage()).doesNotContain(semicolonSecret)
                .contains("Workgroup=secondary", "Region=us-west-2", "AuthToken=[REDACTED]");
        assertThat(semicolon.getCause().getMessage()).doesNotContain(semicolonSecret);
    }

    @Test
    void dirtyDescendantPreservesCopiedSqlAncestorDiagnostics() {
        String secret = secret();
        SQLException original = new SQLException("root", "42000", 11);
        SQLException child = new SQLException("child", "28000", 22);
        StackTraceElement[] rootStack = {frame("Root.run", 10)};
        StackTraceElement[] childStack = {frame("Child.read", 20)};
        original.setStackTrace(rootStack);
        child.setStackTrace(childStack);
        original.initCause(child);
        child.initCause(new Throwable("dirty " + secret));

        SQLException copy = (SQLException) wrap(original, params(secret)).getCause();
        SQLException childCopy = (SQLException) copy.getCause();

        assertThat((Throwable) copy).isNotSameAs(original);
        assertThat(copy.getSQLState()).isEqualTo("42000");
        assertThat(copy.getErrorCode()).isEqualTo(11);
        assertThat(copy.getStackTrace()).containsExactly(rootStack);
        assertThat((Throwable) childCopy).isNotSameAs(child);
        assertThat(childCopy.getSQLState()).isEqualTo("28000");
        assertThat(childCopy.getErrorCode()).isEqualTo(22);
        assertThat(childCopy.getStackTrace()).containsExactly(childStack);
        assertThat(childCopy.getCause().getMessage()).isEqualTo("dirty [REDACTED]");
    }

    @Test
    void nextExceptionChainIsScrubbedAndCleanTailIsRetained() {
        String secret = secret();
        SQLException root = new SQLException("root " + secret);
        SQLException middle = new SQLException("middle " + secret);
        SQLException cleanTail = new SQLException("clean tail");
        root.setNextException(middle);
        middle.setNextException(cleanTail);

        SQLException copy = (SQLException) wrap(root, params(secret)).getCause();
        SQLException middleCopy = copy.getNextException();

        assertThat(copy.getMessage()).isEqualTo("root [REDACTED]");
        assertThat((Throwable) middleCopy).isNotSameAs(middle);
        assertThat(middleCopy.getMessage()).isEqualTo("middle [REDACTED]");
        assertThat((Throwable) middleCopy.getNextException()).isSameAs(cleanTail);
    }

    @Test
    void multiNodeNextExceptionCycleTerminatesAndRemainsCycle() {
        String secret = secret();
        SQLException first = new SQLException("first " + secret);
        SQLException second = new SQLException("second " + secret);
        first.setNextException(second);
        second.setNextException(first);

        SQLException firstCopy = (SQLException) wrap(first, params(secret)).getCause();
        SQLException secondCopy = firstCopy.getNextException();

        assertThat(firstCopy.getMessage()).isEqualTo("first [REDACTED]");
        assertThat(secondCopy.getMessage()).isEqualTo("second [REDACTED]");
        assertThat((Throwable) secondCopy.getNextException()).isSameAs(firstCopy);
        assertThat((Throwable) first.getNextException()).isSameAs(second);
        assertThat((Throwable) second.getNextException()).isSameAs(first);
    }

    @Test
    void sharedCleanNodeRetainsIdentityAcrossCauseAndSuppressedEdges() {
        String secret = secret();
        SQLException root = new SQLException("root " + secret);
        Throwable shared = new Throwable("clean shared child");
        root.initCause(shared);
        root.addSuppressed(shared);

        SQLException copy = (SQLException) wrap(root, params(secret)).getCause();

        assertThat(copy.getCause()).isSameAs(shared);
        assertThat(copy.getSuppressed()).containsExactly(shared);
    }

    @Test
    void sharedDirtyNodeIsCopiedOnceAcrossCauseAndSuppressedEdges() {
        String secret = secret();
        SQLException root = new SQLException("root " + secret);
        Throwable shared = new Throwable("dirty shared child " + secret);
        root.initCause(shared);
        root.addSuppressed(shared);

        SQLException copy = (SQLException) wrap(root, params(secret)).getCause();

        assertThat(copy.getCause()).isNotSameAs(shared);
        assertThat(copy.getCause().getMessage()).isEqualTo("dirty shared child [REDACTED]");
        assertThat(copy.getSuppressed()).containsExactly(copy.getCause());
    }

    @Test
    void suppressedFailuresAreRetainedInOrderWithStackTraces() {
        String secret = secret();
        SQLException root = new SQLException("root " + secret);
        SQLException first = new SQLException("close result set " + secret);
        SQLException second = new SQLException("close connection " + secret);
        StackTraceElement[] firstStack = {frame("First.close", 30)};
        StackTraceElement[] secondStack = {frame("Second.close", 40)};
        first.setStackTrace(firstStack);
        second.setStackTrace(secondStack);
        root.addSuppressed(first);
        root.addSuppressed(second);

        SQLException copy = (SQLException) wrap(root, params(secret)).getCause();

        assertThat(copy.getSuppressed()).hasSize(2);
        assertThat(copy.getSuppressed()[0].getMessage()).isEqualTo("close result set [REDACTED]");
        assertThat(copy.getSuppressed()[0].getStackTrace()).containsExactly(firstStack);
        assertThat(copy.getSuppressed()[1].getMessage()).isEqualTo("close connection [REDACTED]");
        assertThat(copy.getSuppressed()[1].getStackTrace()).containsExactly(secondStack);
    }

    @Test
    void selfReferencingNextExceptionCycleTerminatesAndRemainsCycle() {
        String secret = secret();
        SQLException root = new SQLException("cycle " + secret);
        root.setNextException(root);

        SQLException copy = (SQLException) wrap(root, params(secret)).getCause();

        assertThat((Throwable) copy).isNotSameAs(root);
        assertThat(copy.getMessage()).isEqualTo("cycle [REDACTED]");
        assertThat((Throwable) copy.getNextException()).isSameAs(copy);
    }

    @Test
    void simulatedCauseCycleTerminatesAndOmitsCopiedBackEdge() {
        String firstSecret = secret();
        String secondSecret = secret();
        SQLException root = new SQLException("root " + firstSecret);
        CycleThrowable first = new CycleThrowable("first " + firstSecret);
        CycleThrowable second = new CycleThrowable("second " + secondSecret);
        first.cause = second;
        second.cause = first;
        root.initCause(first);

        Properties properties = new Properties();
        properties.setProperty("SecondaryCredential", secondSecret);
        SQLException copy = (SQLException) wrap(root, params(null, firstSecret, properties)).getCause();
        Throwable firstCopy = copy.getCause();
        Throwable secondCopy = firstCopy.getCause();

        assertThat(copy.getMessage()).isEqualTo("root [REDACTED]");
        assertThat(firstCopy.getMessage()).isEqualTo("first [REDACTED]");
        assertThat(secondCopy.getMessage()).isEqualTo("second [REDACTED]");
        assertThat(secondCopy.getCause()).isNull();
        assertThat(first.cause).isSameAs(second);
        assertThat(second.cause).isSameAs(first);
    }

    @Test
    void suppressedBackEdgeTerminatesWithoutSelfSuppression() {
        String secret = secret();
        SQLException root = new SQLException("root " + secret);
        SQLException child = new SQLException("child " + secret);
        root.addSuppressed(child);
        child.addSuppressed(root);

        SQLException copy = (SQLException) wrap(root, params(secret)).getCause();
        Throwable childCopy = copy.getSuppressed()[0];

        assertThat(copy.getMessage()).isEqualTo("root [REDACTED]");
        assertThat(childCopy.getMessage()).isEqualTo("child [REDACTED]");
        assertThat(copy.getSuppressed()).containsExactly(childCopy);
        assertThat(childCopy.getSuppressed()).isEmpty();
        assertThat(root.getSuppressed()).containsExactly(child);
        assertThat(child.getSuppressed()).containsExactly(root);
    }

    @Test
    void entirelyCleanCyclicGraphIsRetainedByIdentity() {
        SQLException root = new SQLException("clean root");
        CycleThrowable first = new CycleThrowable("clean first");
        CycleThrowable second = new CycleThrowable("clean second");
        first.cause = second;
        second.cause = first;
        root.initCause(first);

        QueryException wrapped = wrap(root, params(secret()));

        assertThat(wrapped.getCause()).isSameAs(root);
        assertThat(wrapped.getCause().getCause()).isSameAs(first);
        assertThat(first.cause).isSameAs(second);
        assertThat(second.cause).isSameAs(first);
    }

    @Test
    void nullAndBlankSecretsLeaveMessagesAndGraphIdentityUntouched() {
        Properties properties = new Properties();
        properties.setProperty("ApiToken", " \t");
        SQLException root = new SQLException("ordinary true text");
        SQLException suppressed = new SQLException("ordinary close failure");
        root.addSuppressed(suppressed);

        QueryException nullSecret = wrap(root, params(null, null, properties));
        QueryException blankSecret = wrap(root, params(null, " ", properties));

        assertThat(nullSecret.getCause()).isSameAs(root);
        assertThat(blankSecret.getCause()).isSameAs(root);
        assertThat(root.getSuppressed()).containsExactly(suppressed);
        assertThat(root.getMessage()).isEqualTo("ordinary true text");
    }

    @Test
    void driverSpecificExceptionIsReplacedByGenericSqlException() {
        String secret = secret();
        DriverSQLException original = new DriverSQLException("driver " + secret);

        Throwable copy = wrap(original, params(secret)).getCause();

        assertThat(copy).isExactlyInstanceOf(SQLException.class);
        assertThat(copy).isNotInstanceOf(DriverSQLException.class);
        assertThat(copy.getMessage()).isEqualTo("driver [REDACTED]");
    }

    @Test
    void originalMessagesAndLinksAreNotMutated() {
        String secret = secret();
        SQLException root = new SQLException("root " + secret);
        SQLException next = new SQLException("next " + secret);
        Throwable cause = new Throwable("cause " + secret);
        SQLException suppressed = new SQLException("suppressed " + secret);
        root.setNextException(next);
        root.initCause(cause);
        root.addSuppressed(suppressed);

        wrap(root, params(secret));

        assertThat(root.getMessage()).isEqualTo("root " + secret);
        assertThat(next.getMessage()).isEqualTo("next " + secret);
        assertThat(cause.getMessage()).isEqualTo("cause " + secret);
        assertThat(suppressed.getMessage()).isEqualTo("suppressed " + secret);
        assertThat((Throwable) root.getNextException()).isSameAs(next);
        assertThat(root.getCause()).isSameAs(cause);
        assertThat(root.getSuppressed()).containsExactly(suppressed);
    }

    @Test
    void veryDeepDirtyCauseChainTerminatesWithoutStackOverflow() {
        String secret = secret();
        int depth = 10_000;
        SQLException root = new SQLException("root");
        Throwable current = root;
        for (int i = 0; i < depth; i++) {
            SQLException next = new SQLException("level " + i);
            current.initCause(next);
            current = next;
        }
        current.initCause(new Throwable("bottom " + secret));

        Throwable copy = wrap(root, params(secret)).getCause();
        for (int i = 0; i < depth; i++) {
            copy = copy.getCause();
        }

        assertThat(copy.getCause().getMessage()).isEqualTo("bottom [REDACTED]");
    }

    private static QueryException wrap(SQLException original, ConnectionParams params) {
        return QueryException.wrap(original, CredentialScrubber.from(params));
    }

    private static ConnectionParams params(String password) {
        return params(null, password, null);
    }

    private static ConnectionParams params(String host, String password, Properties properties) {
        return new ConnectionParams(host, null, "user", password, "database", properties);
    }

    private static String secret() {
        return "secret-" + UUID.randomUUID();
    }

    private static StackTraceElement frame(String method, int line) {
        return new StackTraceElement("example.Driver", method, "Driver.java", line);
    }

    private static final class CycleThrowable extends Throwable {
        private Throwable cause;

        private CycleThrowable(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }

    private static final class DriverSQLException extends SQLException {
        private DriverSQLException(String message) {
            super(message);
        }
    }
}
