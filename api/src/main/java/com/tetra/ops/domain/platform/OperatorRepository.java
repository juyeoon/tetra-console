package com.tetra.ops.domain.platform;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorRepository extends JpaRepository<Operator, Long> {

	Optional<Operator> findByEmail(String email);

}
