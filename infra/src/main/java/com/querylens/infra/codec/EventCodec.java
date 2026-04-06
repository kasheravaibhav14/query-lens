package com.querylens.infra.codec;

public interface EventCodec {
    byte[] serialize(Object event);
    <T> T deserialize(byte[] bytes, Class<T> type);
}
