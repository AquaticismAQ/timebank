package cn.timebank.notification.application;

import cn.timebank.notification.application.dto.AnnouncementDto;

import java.util.List;

public interface AnnouncementService {

    List<AnnouncementDto> listActive(String resolvedLanguage);
}
