package com.erdstudio.io;

import com.erdstudio.model.Diagram;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Output of any importer (SQL, Prisma, DBML, live database).
 *
 * @param keepsColors  true when the source defined table colours (DBML headercolor), so don't recolour
 * @param areaMembers  areaId -> table ids, for areas whose geometry still has to be computed (DBML TableGroups)
 */
public record ImportResult(Diagram diagram, List<String> warnings, boolean keepsColors, Map<String, Set<String>> areaMembers) {
    public ImportResult(Diagram diagram, List<String> warnings) {
        this(diagram, warnings, false, Map.of());
    }

    public int tableCount() { return diagram.tables.size(); }

    public int relationCount() { return diagram.relations.size(); }
}
