package com.testtask.outlookagent.mail.outlook;

import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.ComThread;
import com.jacob.com.Dispatch;
import com.jacob.com.Variant;
import java.util.ArrayList;
import java.util.List;

/**
 * Native Outlook COM adapter behind {@link OutlookComFacade}, using JACOB 1.20
 * (com.jacob.activeX/com.jacob.com) against the Outlook Object Model. Thin by design: no
 * business logic here, only the calls OutlookMailChannel needs. Not exercised by {@code mvn
 * test} - JACOB is excluded from the test classpath (see PLAN.md "JACOB strategy") - status
 * stays "requires target environment verification" until run live against Outlook.
 *
 * <p>Attaches to the Outlook instance already running under the current Windows session
 * (MAPI requires an STA thread per JACOB's threading docs, hence {@code ComThread.InitSTA()}).
 * It never calls {@code Application.Quit} - this facade must not terminate a user's running
 * Outlook session.
 *
 * <p>{@code profile} is resolved as the top-level mail store/account name under
 * {@code Namespace.Folders} and {@code folder} as a folder name within that store; this is the
 * minimal COM-automatable reading of the existing config contract (see
 * {@link com.testtask.outlookagent.config.AppConfig.MailConfig}), not an extension of it.
 */
public class JacobOutlookComFacade implements OutlookComFacade {

    private static final String MAPI_NAMESPACE = "MAPI";

    @Override
    public List<OutlookMailData> fetchUnread(String profile, String folder) {
        ComThread.InitSTA();
        try {
            ActiveXComponent outlookApp = new ActiveXComponent("Outlook.Application");
            try {
                Dispatch namespace = outlookApp.invoke("GetNamespace", new Variant(MAPI_NAMESPACE)).toDispatch();
                Dispatch mailFolder = resolveFolder(namespace, profile, folder);
                Dispatch items = Dispatch.get(mailFolder, "Items").toDispatch();
                Dispatch unreadItems = Dispatch.call(items, "Restrict", "[Unread] = true").toDispatch();

                int count = Dispatch.get(unreadItems, "Count").getInt();
                List<OutlookMailData> result = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    Dispatch mailItem = Dispatch.call(unreadItems, "Item", i).toDispatch();
                    result.add(toMailData(mailItem));
                }
                return result;
            } finally {
                outlookApp.safeRelease();
            }
        } finally {
            ComThread.Release();
        }
    }

    @Override
    public void reply(String entryId, String replyBody) {
        ComThread.InitSTA();
        try {
            ActiveXComponent outlookApp = new ActiveXComponent("Outlook.Application");
            try {
                Dispatch namespace = outlookApp.invoke("GetNamespace", new Variant(MAPI_NAMESPACE)).toDispatch();
                Dispatch original = Dispatch.call(namespace, "GetItemFromID", entryId).toDispatch();
                Dispatch replyItem = Dispatch.call(original, "Reply").toDispatch();
                Dispatch.put(replyItem, "Body", replyBody);
                Dispatch.call(replyItem, "Send");
            } finally {
                outlookApp.safeRelease();
            }
        } finally {
            ComThread.Release();
        }
    }

    private Dispatch resolveFolder(Dispatch namespace, String profile, String folder) {
        Dispatch stores = Dispatch.get(namespace, "Folders").toDispatch();
        Dispatch store = Dispatch.call(stores, "Item", profile).toDispatch();
        Dispatch storeFolders = Dispatch.get(store, "Folders").toDispatch();
        return Dispatch.call(storeFolders, "Item", folder).toDispatch();
    }

    private OutlookMailData toMailData(Dispatch mailItem) {
        String entryId = Dispatch.get(mailItem, "EntryID").getString();
        String sender = Dispatch.get(mailItem, "SenderEmailAddress").getString();
        String subject = Dispatch.get(mailItem, "Subject").getString();
        String body = Dispatch.get(mailItem, "Body").getString();
        return new OutlookMailData(entryId, sender, subject, body);
    }
}
