package com.nexus.notification.application.readstate;

import com.nexus.notification.domain.readstate.ChannelReadState;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn read state của user trên các kênh họ còn truy cập được
@Service
public class ListReadStatesQuery {

  private final ReadStateRepository readStates;

  // Nhận repository read state qua constructor
  public ListReadStatesQuery(ReadStateRepository readStates) {
    this.readStates = readStates;
  }

  // Trả read state của user theo thứ tự channelId ổn định
  @Transactional(readOnly = true)
  public List<ChannelReadState> execute(UserId user) {
    return readStates.listFor(user);
  }
}
