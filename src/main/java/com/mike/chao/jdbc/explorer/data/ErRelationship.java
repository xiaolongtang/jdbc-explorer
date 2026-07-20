package com.mike.chao.jdbc.explorer.data;

public record ErRelationship(
    String fromTable,
    String fromColumn,
    String toTable,
    String toColumn,
    RelationshipSource source,
    double confidence
) {}
