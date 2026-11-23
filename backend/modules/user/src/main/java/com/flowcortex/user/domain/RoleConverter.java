package com.flowcortex.user.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link Role} to the {@code app_user.role} code column. */
@Converter
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role role) {
        return role == null ? null : role.code();
    }

    @Override
    public Role convertToEntityAttribute(String code) {
        return code == null ? null : Role.of(code);
    }
}
