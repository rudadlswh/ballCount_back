package com.kbo.crawlerapi.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class GameCancelReasonConverter implements AttributeConverter<GameCancelReason, String> {

    @Override
    public String convertToDatabaseColumn(GameCancelReason attribute) {
        return attribute == null ? null : attribute.getApiValue();
    }

    @Override
    public GameCancelReason convertToEntityAttribute(String dbData) {
        return dbData == null ? null : GameCancelReason.fromApiValue(dbData);
    }
}
