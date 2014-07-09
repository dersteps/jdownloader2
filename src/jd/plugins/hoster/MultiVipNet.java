//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "multivip.net" }, urls = { "REGEX_NOT_POSSIBLE_RANDOM-asdfasdfsadfsfs2133" }, flags = { 2 })
public class MultiVipNet extends PluginForHost {

    private static HashMap<Account, HashMap<String, Long>> hostUnavailableMap = new HashMap<Account, HashMap<String, Long>>();
    private static final String                            NOCHUNKS           = "NOCHUNKS";

    private static final String                            NICE_HOST          = "multivip.net";
    private static final String                            NICE_HOSTproperty  = "multivipnet";
    private static final String                            APIKEY             = "amQy";
    private static final boolean                           USE_API            = true;

    /* Default value is 10 */
    private static AtomicInteger                           maxPrem            = new AtomicInteger(10);

    public MultiVipNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://multivip.net/");
        this.setAccountwithoutUsername(true);
    }

    @Override
    public String getAGBLink() {
        return "http://multivip.net/contact.php";
    }

    private Browser newBrowser() {
        br = new Browser();
        br.setCookiesExclusive(true);
        // define custom browser headers
        br.getHeaders().put("Accept", "application/json");
        br.getHeaders().put("User-Agent", "JDownloader");
        br.setCustomCharset("utf-8");
        br.setCookie("http://multivip.net/", "lang", "en");
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink link) throws PluginException {
        return AvailableStatus.UNCHECKABLE;
    }

    @Override
    public boolean canHandle(DownloadLink downloadLink, Account account) {
        if (account == null) {
            /* without account its not possible to download the link */
            return false;
        }
        synchronized (hostUnavailableMap) {
            HashMap<String, Long> unavailableMap = hostUnavailableMap.get(account);
            if (unavailableMap != null) {
                Long lastUnavailable = unavailableMap.get(downloadLink.getHost());
                if (lastUnavailable != null && System.currentTimeMillis() < lastUnavailable) {
                    return false;
                } else if (lastUnavailable != null) {
                    unavailableMap.remove(downloadLink.getHost());
                    if (unavailableMap.size() == 0) {
                        hostUnavailableMap.remove(account);
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception, PluginException {
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 0;
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        /* handle premium should never be called */
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    private void handleDL(final Account account, final DownloadLink link, final String dllink) throws Exception {
        /* we want to follow redirects in final stage */
        br.setFollowRedirects(true);
        br.setCurrentURL(null);
        int maxChunks = -2;
        if (link.getBooleanProperty(NOCHUNKS, false)) {
            maxChunks = 1;
        }
        link.setProperty("multivipnetdirectlink", dllink);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, maxChunks);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            logger.info(NICE_HOST + ": Unknown download error");
            int timesFailed = link.getIntegerProperty(NICE_HOSTproperty + "timesfailed_unknowndlerror", 0);
            link.getLinkStatus().setRetryCount(0);
            if (timesFailed <= 2) {
                timesFailed++;
                link.setProperty(NICE_HOSTproperty + "timesfailed_unknowndlerror", timesFailed);
                throw new PluginException(LinkStatus.ERROR_RETRY, "Unknown error");
            } else {
                link.setProperty(NICE_HOSTproperty + "timesfailed_unknowndlerror", Property.NULL);
                logger.info(NICE_HOST + ": Unknown error - disabling current host!");
                tempUnavailableHoster(account, link, 60 * 60 * 1000l);
            }
        }
        try {
            if (!this.dl.startDownload()) {
                try {
                    if (dl.externalDownloadStop()) {
                        return;
                    }
                } catch (final Throwable e) {
                }
                /* unknown error, we disable multiple chunks */
                if (link.getBooleanProperty(MultiVipNet.NOCHUNKS, false) == false) {
                    link.setProperty(MultiVipNet.NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        } catch (final PluginException e) {
            // New V2 errorhandling
            /* unknown error, we disable multiple chunks */
            if (e.getLinkStatus() != LinkStatus.ERROR_RETRY && link.getBooleanProperty(MultiVipNet.NOCHUNKS, false) == false) {
                link.setProperty(MultiVipNet.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            throw e;
        }
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        this.br = newBrowser();
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        showMessage(link, "Task 1: Generating Link");
        String dllink = checkDirectLink(link, "multivipnetdirectlink");
        if (dllink == null) {
            /* request Download */
            if (USE_API) {
                br.getPage("http://multivip.net/api.php?apipass=" + Encoding.Base64Decode(APIKEY) + "&do=addlink&vipkey=" + Encoding.urlEncode(account.getPass()) + "&ip=&link=" + Encoding.urlEncode(link.getDownloadURL()));
                if ("204".equals(getJson(br.toString(), "error"))) {
                    account.setType(AccountType.FREE);
                    account.getAccountInfo().setStatus("Free Account");
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nKostenloser Key - dieser funktioniert nur zum Download kleiner Dateien!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nFree key - it is limited to download small files!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                dllink = getJson(br.toString(), "directlink");
            } else {
                br.postPage("http://multivip.net/links.php", "do=addlinks&links=" + Encoding.urlEncode(link.getDownloadURL()) + "&vipkey=" + Encoding.urlEncode(account.getPass()));
                if (br.containsHTML("Universal VIP key is missing or incorrect")) {
                    logger.info("Given Vip key is invalid");
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Vip key!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid Vip key!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else if (br.containsHTML("This is a FREE key and File size in")) {
                    account.setType(AccountType.FREE);
                    account.getAccountInfo().setStatus("Free Account");
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nKostenloser Key - dieser funktioniert nur zum Download kleiner Dateien!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nFree key - it is limited to download small files!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else if (br.containsHTML("Unfortunately this key was expired")) {
                    /* Our account has expired */
                    final String expire_date = br.getRegex("Unfortunately this key was expired <strong>([^<>\"]*?)</strong>").getMatch(0);
                    if (expire_date != null) {
                        account.getAccountInfo().setValidPremiumUntil(TimeFormatter.getMilliSeconds(expire_date, "MM-dd-yy, hh:mm", Locale.ENGLISH));
                    }
                    account.getAccountInfo().setExpired(true);
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else if (br.containsHTML("Failed to get information about the file")) {
                    logger.info("Seems like the current host doesn't work anymore --> Disabling it");
                    tempUnavailableHoster(account, link, 60 * 60 * 1000l);
                }
                final Regex account_info = br.getRegex("you have after this action <strong>([^<>\"]*?)</strong> till <strong>([^<>\"]*?) </strong>");
                final String traffic_left = account_info.getMatch(0);
                final String expire_date = account_info.getMatch(1);
                if (traffic_left != null && expire_date != null) {
                    account.getAccountInfo().setTrafficLeft(SizeFormatter.getSize(traffic_left));
                    account.getAccountInfo().setValidPremiumUntil(TimeFormatter.getMilliSeconds(expire_date, "MM-dd-yy, hh:mm", Locale.ENGLISH));
                }
                dllink = br.getRegex("\"(http[^<>\"]*?)\"").getMatch(0);
            }
            if (dllink == null) {
                logger.info(NICE_HOST + ": Unknown error");
                int timesFailed = link.getIntegerProperty(NICE_HOSTproperty + "timesfailed_unknown", 0);
                link.getLinkStatus().setRetryCount(0);
                if (timesFailed <= 2) {
                    timesFailed++;
                    link.setProperty(NICE_HOSTproperty + "timesfailed_unknown", timesFailed);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Unknown error");
                } else {
                    link.setProperty(NICE_HOSTproperty + "timesfailed_unknown", Property.NULL);
                    logger.info(NICE_HOST + ": Unknown error - disabling current host!");
                    tempUnavailableHoster(account, link, 60 * 60 * 1000l);
                }
            }
            dllink = dllink.replace("\\", "");
        }
        showMessage(link, "Task 2: Download begins!");
        handleDL(account, link, dllink);
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                final Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        this.br = newBrowser();
        final AccountInfo ai = new AccountInfo();
        account.setMaxSimultanDownloads(20);
        maxPrem.set(20);
        br.getPage("http://multivip.net/api.php?apipass=" + Encoding.Base64Decode(APIKEY) + "&do=keycheck&vipkey=" + Encoding.urlEncode(account.getPass()));
        final String error = getJson(br.toString(), "error");
        if (error != null) {
            if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Vip key!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid Vip key!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        final String expire = getJson(br.toString(), "diedate");
        ai.setValidUntil(Long.parseLong(expire) * 1000);
        br.getPage("http://multivip.net/api.php?apipass=" + Encoding.Base64Decode(APIKEY) + "&do=getlist");
        final ArrayList<String> supportedHosts = new ArrayList<String>();
        final String[] hostDomains = br.getRegex("\"allow\":\\[(.*?)\\]").getColumn(0);
        for (final String domains : hostDomains) {
            final String[] realDomains = new Regex(domains, "\"(.*?)\"").getColumn(0);
            for (final String realDomain : realDomains) {
                supportedHosts.add(realDomain);
            }
        }
        if (supportedHosts.contains("uploaded.net")) {
            supportedHosts.add("ul.to");
            supportedHosts.add("uploaded.to");
        }
        /*
         * They also got free accounts / free "Vip keys" but status is only visible whenever the max downloadable filesize limit of a (free)
         * account is reached
         */
        account.setType(AccountType.PREMIUM);
        ai.setStatus("Premium Account");
        ai.setMultiHostSupport(supportedHosts);
        ai.setUnlimitedTraffic();
        account.setValid(true);
        return ai;
    }

    private void showMessage(DownloadLink link, String message) {
        link.getLinkStatus().setStatusText(message);
    }

    private String getJson(final String source, final String parameter) {
        String result = new Regex(source, "\"" + parameter + "\":([\t\n\r ]+)?([0-9\\.]+)").getMatch(1);
        if (result == null) {
            result = new Regex(source, "\"" + parameter + "\":([\t\n\r ]+)?\"([^<>\"]*?)\"").getMatch(1);
        }
        return result;
    }

    private void tempUnavailableHoster(final Account account, final DownloadLink downloadLink, final long timeout) throws PluginException {
        if (downloadLink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unable to handle this errorcode!");
        }
        synchronized (hostUnavailableMap) {
            HashMap<String, Long> unavailableMap = hostUnavailableMap.get(account);
            if (unavailableMap == null) {
                unavailableMap = new HashMap<String, Long>();
                hostUnavailableMap.put(account, unavailableMap);
            }
            /* wait 30 mins to retry this host */
            unavailableMap.put(downloadLink.getHost(), (System.currentTimeMillis() + timeout));
        }
        throw new PluginException(LinkStatus.ERROR_RETRY);
    }

    @Override
    public int getMaxSimultanDownload(final DownloadLink link, final Account account) {
        return maxPrem.get();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}