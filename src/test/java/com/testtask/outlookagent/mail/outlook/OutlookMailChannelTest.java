package com.testtask.outlookagent.mail.outlook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.testtask.outlookagent.mail.Msg;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * RED spec (Stage 33): Outlook/JACOB COM boundary contract. Fixes the
 * architecture core -> MailChannel -> OutlookMailChannel -> thin COM facade
 * -> JACOB via a fake OutlookComFacade. References OutlookMailChannel,
 * OutlookComFacade and OutlookMailData in package mail.outlook, none of
 * which exist yet - compile-level RED. No com.jacob.* import here; the real
 * JACOB adapter is a separate GREEN-only infrastructure class, not loaded by
 * this test. See PLAN.md "JACOB strategy" and Тестовое-задание §5.
 */
public class OutlookMailChannelTest {

    private static final String CONFIGURED_PROFILE = "TestProfile";
    private static final String CONFIGURED_FOLDER = "Inbox";

    @Test
    public void fetchUnreadMapsOutlookDataToMsgAndPassesConfiguredProfileAndFolder() {
        FakeOutlookComFacade facade = new FakeOutlookComFacade();
        facade.unreadToReturn = Collections.singletonList(
                new OutlookMailData("stable-entry-id-123", "sender@example.test", "subject text", "body text"));

        OutlookMailChannel channel = new OutlookMailChannel(facade, CONFIGURED_PROFILE, CONFIGURED_FOLDER);

        List<Msg> messages = channel.fetchUnread();

        assertEquals(1, messages.size());
        Msg msg = messages.get(0);
        assertEquals("stable-entry-id-123", msg.getId());
        assertEquals("sender@example.test", msg.getSender());
        assertEquals("subject text", msg.getSubject());
        assertEquals("body text", msg.getBody());

        assertEquals(CONFIGURED_PROFILE, facade.fetchUnreadCalledWithProfile);
        assertEquals(CONFIGURED_FOLDER, facade.fetchUnreadCalledWithFolder);
    }

    @Test
    public void fetchUnreadUsesStableEntryIdNotSubjectOrBodyAsMsgId() {
        FakeOutlookComFacade facade = new FakeOutlookComFacade();
        facade.unreadToReturn = Collections.singletonList(
                new OutlookMailData("entry-id-should-be-key", "sender@example.test", "same text", "same text"));

        OutlookMailChannel channel = new OutlookMailChannel(facade, CONFIGURED_PROFILE, CONFIGURED_FOLDER);
        Msg msg = channel.fetchUnread().get(0);

        assertEquals("entry-id-should-be-key", msg.getId());
        assertNotEquals("same text", msg.getId());
    }

    @Test
    public void replyDelegatesThroughComBoundaryUsingStableMessageIdNotSubjectOrBody() {
        FakeOutlookComFacade facade = new FakeOutlookComFacade();
        OutlookMailChannel channel = new OutlookMailChannel(facade, CONFIGURED_PROFILE, CONFIGURED_FOLDER);
        Msg msg = new Msg("stable-entry-id-456", "sender@example.test", "subject", "body");

        channel.reply(msg, "reply body text");

        assertEquals("stable-entry-id-456", facade.replyCalledWithEntryId);
        assertEquals("reply body text", facade.replyCalledWithBody);
    }

    private static class FakeOutlookComFacade implements OutlookComFacade {
        List<OutlookMailData> unreadToReturn = new ArrayList<>();
        String fetchUnreadCalledWithProfile;
        String fetchUnreadCalledWithFolder;
        String replyCalledWithEntryId;
        String replyCalledWithBody;

        @Override
        public List<OutlookMailData> fetchUnread(String profile, String folder) {
            fetchUnreadCalledWithProfile = profile;
            fetchUnreadCalledWithFolder = folder;
            return unreadToReturn;
        }

        @Override
        public void reply(String entryId, String replyBody) {
            replyCalledWithEntryId = entryId;
            replyCalledWithBody = replyBody;
        }
    }
}
