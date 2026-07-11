package uz.greenwhite.gateway.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class AdminAccount {
    private Long id;
    private String username;
    private String passwordHash;
    private LocalDateTime lastLoginAt;
}
