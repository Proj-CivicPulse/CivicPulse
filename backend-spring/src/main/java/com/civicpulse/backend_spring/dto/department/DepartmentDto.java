package com.civicpulse.backend_spring.dto.department;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.entity.Department;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DepartmentDto {

    private final String id;
    private final String name;

    public static DepartmentDto from(Department department) {
        return new DepartmentDto(Wire.id(department.getId()), department.getName());
    }
}
