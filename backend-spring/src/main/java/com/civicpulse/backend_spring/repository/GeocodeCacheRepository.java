package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.GeocodeCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GeocodeCacheRepository extends JpaRepository<GeocodeCache, GeocodeCache.Key> {
}
