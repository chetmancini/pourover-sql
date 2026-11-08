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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Unchecked wrapper for a {@link SQLException} raised while connecting, querying or reading rows.
 *
 * <p>When Pourover knows connection credentials (via {@link Pourover#connect}), they are replaced
 * with {@code [REDACTED]} in this message and throughout reachable JDBC cause, next-exception and
 * suppressed diagnostics. Sanitization is iterative and cycle-safe. Clean nodes retain their identity;
 * copied SQL exceptions retain SQL state, vendor codes and stack traces. Cause or suppressed back edges
 * that cannot be represented safely on generic copies are terminated.
 */
public class QueryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

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
     * Wraps a JDBC error, removing known credentials throughout its diagnostic graph.
     * @param e the JDBC error
     * @param scrubber credentials known for this connection
     * @return the wrapping exception
     */
    static QueryException wrap(SQLException e, CredentialScrubber scrubber) {
        SQLException cause = sanitize(e, scrubber);
        String driverMessage = cause.getMessage() == null ? "" : cause.getMessage();
        StringBuilder message = new StringBuilder("Query failed");
        if (e.getSQLState() != null) {
            message.append(" [SQLState ").append(e.getSQLState()).append(']');
        }
        message.append(": ").append(driverMessage);
        return new QueryException(message.toString(), cause);
    }

    private static SQLException sanitize(SQLException root, CredentialScrubber scrubber) {
        Map<Throwable, Node> nodes = new IdentityHashMap<>();
        List<Node> ordered = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (nodes.containsKey(current)) {
                continue;
            }
            Node node = new Node(current, scrubber);
            nodes.put(current, node);
            ordered.add(node);
            if (node.cause != null) {
                pending.addLast(node.cause);
            }
            if (node.next != null) {
                pending.addLast(node.next);
            }
            for (Throwable suppressed : node.suppressed) {
                pending.addLast(suppressed);
            }
        }

        Map<Throwable, List<Throwable>> parents = new IdentityHashMap<>();
        for (Node node : ordered) {
            addParent(parents, node.cause, node.original);
            addParent(parents, node.next, node.original);
            for (Throwable suppressed : node.suppressed) {
                addParent(parents, suppressed, node.original);
            }
        }

        Set<Throwable> replacements = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> dirty = new ArrayDeque<>();
        for (Node node : ordered) {
            if (node.dirty && replacements.add(node.original)) {
                dirty.add(node.original);
            }
        }
        while (!dirty.isEmpty()) {
            Throwable current = dirty.removeFirst();
            for (Throwable parent : parents.getOrDefault(current, List.of())) {
                if (replacements.add(parent)) {
                    dirty.addLast(parent);
                }
            }
        }
        if (replacements.isEmpty()) {
            return root;
        }

        for (Node node : ordered) {
            if (!replacements.contains(node.original)) {
                continue;
            }
            if (node.original instanceof SQLException sql) {
                node.copy = new SQLException(node.message, sql.getSQLState(), sql.getErrorCode());
            } else {
                node.copy = new Throwable(node.message);
            }
            node.copy.setStackTrace(node.original.getStackTrace());
        }

        for (Node node : ordered) {
            if (node.next != null && replacements.contains(node.original)) {
                SQLException copy = (SQLException) node.copy;
                copy.setNextException((SQLException) nodes.get(node.next).copy);
            }
        }
        linkCauseAndSuppressed(ordered, nodes, replacements);
        return (SQLException) nodes.get(root).copy;
    }

    private static void addParent(Map<Throwable, List<Throwable>> parents, Throwable child, Throwable parent) {
        if (child != null) {
            parents.computeIfAbsent(child, ignored -> new ArrayList<>()).add(parent);
        }
    }

    private static void linkCauseAndSuppressed(
            List<Node> ordered, Map<Throwable, Node> nodes, Set<Throwable> replacements) {
        Set<Throwable> linked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node start : ordered) {
            if (!replacements.contains(start.original) || linked.contains(start.copy)) {
                continue;
            }
            Set<Throwable> active = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(start));
            active.add(start.copy);
            linked.add(start.copy);
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                Node node = frame.node;
                if (!frame.causeDone) {
                    frame.causeDone = true;
                    if (node.cause != null) {
                        Throwable child = nodes.get(node.cause).copy;
                        if (child != node.copy && !active.contains(child)) {
                            node.copy.initCause(child);
                            if (replacements.contains(node.cause) && linked.add(child)) {
                                Node childNode = nodes.get(node.cause);
                                stack.push(new Frame(childNode));
                                active.add(child);
                                continue;
                            }
                        }
                    }
                }
                if (frame.suppressedIndex < node.suppressed.size()) {
                    Throwable originalChild = node.suppressed.get(frame.suppressedIndex++);
                    Throwable child = nodes.get(originalChild).copy;
                    if (child != node.copy && !active.contains(child)) {
                        node.copy.addSuppressed(child);
                        if (replacements.contains(originalChild) && linked.add(child)) {
                            stack.push(new Frame(nodes.get(originalChild)));
                            active.add(child);
                        }
                    }
                    continue;
                }
                active.remove(node.copy);
                stack.pop();
            }
        }
    }

    private static final class Node {
        private final Throwable original;
        private final String message;
        private final Throwable cause;
        private final SQLException next;
        private final List<Throwable> suppressed;
        private final boolean dirty;
        private Throwable copy;

        private Node(Throwable original, CredentialScrubber scrubber) {
            this.original = original;
            String originalMessage = original.getMessage();
            this.message = scrubber.scrub(originalMessage);
            this.dirty = !Objects.equals(originalMessage, message);
            this.cause = original.getCause();
            this.next = original instanceof SQLException sql ? sql.getNextException() : null;
            this.suppressed = Arrays.asList(original.getSuppressed());
            this.copy = original;
        }
    }

    private static final class Frame {
        private final Node node;
        private boolean causeDone;
        private int suppressedIndex;

        private Frame(Node node) {
            this.node = node;
        }
    }
}
