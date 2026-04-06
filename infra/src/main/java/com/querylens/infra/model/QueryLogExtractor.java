package com.querylens.infra.model;

import java.util.Optional;

public abstract class QueryLogExtractor<T extends QueryLog> {
    public abstract DatabaseType dbType();
    public abstract Optional<T> extract(LogEnvelope envelope);
}
