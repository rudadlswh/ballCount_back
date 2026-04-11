package com.kbo.crawlerapi.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class GameStatusConverter implements AttributeConverter<GameStatus, String> {

    @Override
    public String convertToDatabaseColumn(GameStatus attribute) {
        return attribute == null ? null : attribute.getApiValue();
    }

    @Override
    public GameStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : GameStatus.fromApiValue(dbData);
    }
}
