package com.mike.chao.jdbc.explorer.data;

import java.util.List;

public record ErDiagram(
    String format,
    String mediaType,
    String svg,
    String dataUri,
    List<TableDetails> tables,
    List<ErRelationship> relationships
) {}
