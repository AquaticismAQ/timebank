package cn.timebank.notification.infrastructure;

import cn.timebank.notification.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, String> {

    @Query("select a from Announcement a where a.active = true "
            + "and (a.startsAt is null or a.startsAt <= :now) "
            + "and (a.endsAt is null or a.endsAt >= :now) "
            + "order by a.createdAt desc")
    List<Announcement> findActive(Instant now);
}
