package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.department.DepartmentDto;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reference data for the officer's triage picker (PATCH sets departmentId).
 * Falls through to anyRequest().authenticated() — not public, but not
 * officer-only either, since it discloses nothing but department names.
 */
@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentRepository departmentRepository;

    @GetMapping
    public ResponseEntity<List<DepartmentDto>> list() {
        return ResponseEntity.ok(
                departmentRepository.findAll().stream().map(DepartmentDto::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DepartmentDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(departmentRepository.findById(id)
                .map(DepartmentDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found")));
    }
}
