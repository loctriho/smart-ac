package com.smartac.device.service;

import com.smartac.admin.push.AdminPushService;
import com.smartac.device.api.dto.DeviceRegistrationRequest;
import com.smartac.device.api.dto.DeviceRegistrationResponse;
import com.smartac.device.model.Device;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.security.TokenHasher;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceRegistrationService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final DeviceRepository deviceRepository;
  private final AdminPushService adminPushService;

  public DeviceRegistrationService(DeviceRepository deviceRepository, AdminPushService adminPushService) {
    this.deviceRepository = deviceRepository;
    this.adminPushService = adminPushService;
  }

  @Transactional
  public DeviceRegistrationResponse register(DeviceRegistrationRequest req) {
    String serial = req.serialNumber().trim();
    if (deviceRepository.existsBySerialNumberIgnoreCase(serial)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Device with this serial number is already registered");
    }
    String tokenPlain = generateToken();
    String hash = TokenHasher.sha256Hex(tokenPlain);

    Device d = new Device();
    d.setSerialNumber(serial);
    d.setFirmwareVersion(req.firmwareVersion().trim());
    d.setApiTokenHash(hash);
    d = deviceRepository.save(d);

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              adminPushService.notifyDevicesChanged();
            }
          });
    } else {
      adminPushService.notifyDevicesChanged();
    }

    return new DeviceRegistrationResponse(
        d.getId(), d.getSerialNumber(), d.getRegistrationDate(), d.getFirmwareVersion(), tokenPlain);
  }

  private static String generateToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "sac_" + HexFormat.of().formatHex(bytes);
  }
}
