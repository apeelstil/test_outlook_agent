package com.testtask.outlookagent.mail.outlook;

import java.util.List;

public interface OutlookComFacade {

    List<OutlookMailData> fetchUnread(String profile, String folder);

    void reply(String entryId, String replyBody);
}
