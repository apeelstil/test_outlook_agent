package com.testtask.outlookagent.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import org.junit.Test;

/**
 * RED spec (Roadmap #3): describes the expected Msg/MailChannel/MockMailChannel
 * contract before any production code exists. See PLAN.md TDD roadmap and
 * Тестовое-задание §3.1 for the required MailChannel shape.
 */
public class MockMailChannelTest {

    @Test
    public void fetchesUnreadAndRecordsReply() {
        Msg incoming = new Msg("msg-1", "sender@example.test", "Test subject", "Test body");

        MockMailChannel mock = new MockMailChannel();
        mock.addUnread(incoming);

        MailChannel channel = mock;

        List<Msg> unread = channel.fetchUnread();

        assertEquals(1, unread.size());
        Msg fetched = unread.get(0);
        assertEquals("msg-1", fetched.getId());
        assertEquals("sender@example.test", fetched.getSender());
        assertEquals("Test subject", fetched.getSubject());
        assertEquals("Test body", fetched.getBody());

        channel.reply(fetched, "response text");

        String recordedReply = mock.getReplyBody("msg-1");
        assertNotNull(recordedReply);
        assertEquals("response text", recordedReply);
    }
}
