package com.bot.aabot.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ClassName: GuideMessge
 * Package: com.bot.aabot.entity
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuideMessage {
    public String reply;
    public String guide1;
    public String guide2;
    public String guide3;
}
