package de.vvwt.dispatcher.cache;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link CachedResultEntity}.
 *
 * <p>Provides standard CRUD operations. The composite key type is {@link CachedResultId}.
 */
public interface CachedResultRepository extends JpaRepository<CachedResultEntity, CachedResultId> {}
