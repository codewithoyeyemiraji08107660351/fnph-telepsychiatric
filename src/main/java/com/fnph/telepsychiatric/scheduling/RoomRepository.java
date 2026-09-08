package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findByPublicId(String publicId);
    Optional<Room> findByCode(String code);
    List<Room> findAllByIsActiveTrueOrderByCodeAsc();
    List<Room> findAllByRoomTypeAndIsActiveTrueOrderByCodeAsc(RoomType roomType);
}
