package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A named snapshot of the schema, stored inside the diagram file. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Version {
    public String id = Ids.next();
    public String name = "";
    public String note = "";
    /** ISO-8601 local date-time. */
    public String createdAt = "";
    /** The diagram (without its own versions) serialized as JSON. */
    public String snapshot = "";
}
