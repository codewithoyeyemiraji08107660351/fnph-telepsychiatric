package com.fnph.telepsychiatric.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByPublicId(String publicId);

    /**
     * A principal's own items plus their dashboard's.
     *
     * The centre condition is what stops one centre's coordinator seeing
     * another's queue: a role-addressed item carrying a centre is only visible
     * to holders of that role at that centre.
     */
    @Query("""
           select n from Notification n
           where n.channel = com.fnph.telepsychiatric.notification.NotificationChannel.IN_APP
             and n.dismissedAt is null
             and (
                   n.user.id = :userId
                or (n.targetRole.code in :roleCodes
                    and (n.centre is null or n.centre.id = :centreId))
             )
             and (:unreadOnly = false or n.readAt is null)
           order by n.createdAt desc
           """)
    List<Notification> findInbox(@Param("userId") Long userId,
                                 @Param("roleCodes") List<String> roleCodes,
                                 @Param("centreId") Long centreId,
                                 @Param("unreadOnly") boolean unreadOnly);

    @Query("""
           select count(n) from Notification n
           where n.channel = com.fnph.telepsychiatric.notification.NotificationChannel.IN_APP
             and n.readAt is null and n.dismissedAt is null
             and (
                   n.user.id = :userId
                or (n.targetRole.code in :roleCodes
                    and (n.centre is null or n.centre.id = :centreId))
             )
           """)
    long countUnread(@Param("userId") Long userId,
                     @Param("roleCodes") List<String> roleCodes,
                     @Param("centreId") Long centreId);

    @Modifying
    @Query("""
           update Notification n
              set n.readAt = :now,
                  n.status = com.fnph.telepsychiatric.notification.DeliveryStatus.READ
            where n.readAt is null
              and n.channel = com.fnph.telepsychiatric.notification.NotificationChannel.IN_APP
              and (
                    n.user.id = :userId
                 or (n.targetRole.code in :roleCodes
                     and (n.centre is null or n.centre.id = :centreId))
              )
           """)
    int markInboxRead(@Param("userId") Long userId,
                      @Param("roleCodes") List<String> roleCodes,
                      @Param("centreId") Long centreId,
                      @Param("now") LocalDateTime now);

    List<Notification> findAllByEntityTypeAndEntityId(String entityType, Long entityId);
}
