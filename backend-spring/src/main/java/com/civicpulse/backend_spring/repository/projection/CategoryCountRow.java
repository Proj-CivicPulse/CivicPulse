package com.civicpulse.backend_spring.repository.projection;

/** Complaint volume grouped by category, for the dashboard summary. */
public interface CategoryCountRow {

    String getCategory();

    Long getTotal();
}
