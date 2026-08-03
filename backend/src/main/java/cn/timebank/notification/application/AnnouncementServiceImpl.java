package cn.timebank.notification.application;

import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.notification.application.dto.AnnouncementDto;
import cn.timebank.notification.domain.Announcement;
import cn.timebank.notification.infrastructure.AnnouncementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementRepository repository;

    AnnouncementServiceImpl(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementDto> listActive(String resolvedLanguage) {
        return repository.findActive(Instant.now()).stream()
                .map(a -> new AnnouncementDto(a.getId(),
                        LocalizedNames.pick(resolvedLanguage, a.getTitleZh(), a.getTitleEn()),
                        LocalizedNames.pick(resolvedLanguage, a.getBodyZh(), a.getBodyEn()),
                        a.getStartsAt(), a.getEndsAt(), a.getCreatedAt()))
                .toList();
    }
}
