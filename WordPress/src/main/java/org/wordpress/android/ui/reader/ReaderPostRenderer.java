package org.wordpress.android.ui.reader;

import android.net.Uri;
import android.os.Handler;

import org.wordpress.android.models.ReaderAttachment;
import org.wordpress.android.models.ReaderAttachmentList;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.ui.reader.utils.ReaderImageScanner;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.PhotonUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.UrlUtils;

import java.lang.ref.WeakReference;

/**
 * generates and displays the HTML for post detail content - main purpose is to assign the
 * height/width attributes on image tags to (1) avoid the webView resizing as images are
 * loaded, and (2) avoid requesting images at a size larger than the display
 */
public class ReaderPostRenderer {

    private final ReaderResourceVars mResourceVars;
    private final ReaderPost mPost;
    private final WeakReference<ReaderWebView> mWeakWebView;
    private final ReaderAttachmentList mAttachments;
    private StringBuilder mRenderBuilder;

    ReaderPostRenderer(ReaderWebView webView, ReaderPost post) {
        if (webView == null) {
            throw new IllegalArgumentException("ReaderPostRenderer requires a webView");
        }
        if (post == null) {
            throw new IllegalArgumentException("ReaderPostRenderer requires a post");
        }

        mPost = post;
        mWeakWebView = new WeakReference<ReaderWebView>(webView);
        mResourceVars = new ReaderResourceVars(webView.getContext());
        mAttachments = (mPost.hasAttachments() ? mPost.getAttachments() : new ReaderAttachmentList());
    }

    void beginRender() {
        // start with the basic content
        final String content = getPostContent(mPost);
        mRenderBuilder = new StringBuilder(content);

        // start image scanner to find images so we can replace them with ones that have h/w set
        final Handler handler = new Handler();
        new Thread() {
            @Override
            public void run() {
                ReaderImageScanner.ImageScanListener imageListener = new ReaderImageScanner.ImageScanListener() {
                    @Override
                    public void onImageFound(String imageTag, String imageUrl, int start, int end) {
                        replaceImageTag(imageTag, imageUrl);
                    }

                    @Override
                    public void onScanCompleted() {
                        final String htmlContent = formatPostContentForWebView(mRenderBuilder.toString());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                renderContent(htmlContent);
                            }
                        });
                    }
                };
                ReaderImageScanner scanner = new ReaderImageScanner(content, mPost.isPrivate);
                scanner.beginScan(imageListener);
            }
        }.start();
    }

    /*
     * called once the content is ready to be rendered in the webView
     */
    private void renderContent(final String htmlContent) {
        // make sure webView is still valid (containing fragment may have been detached)
        ReaderWebView webView = mWeakWebView.get();
        if (webView == null) {
            AppLog.w(AppLog.T.READER, "reader renderer > null webView");
            return;
        }

        // IMPORTANT: use loadDataWithBaseURL() since loadData() may fail
        // https://code.google.com/p/android/issues/detail?id=4401
        // also important to use null as the baseUrl since onPageFinished
        // doesn't appear to fire when it's set to an actual url
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
    }

    /*
     * called when image scanner finds an image
     */
    private void replaceImageTag(final String imageTag, final String imageUrl) {
        int origWidth;
        int origHeight;

        // first try to get original image size from attachments, then try to get it from the url
        // if it's an obvious WordPress image
        ReaderAttachment attach = mAttachments.get(imageUrl);
        if (attach != null && attach.isImage()) {
            origWidth = attach.width;
            origHeight = attach.height;
        } else if (imageUrl.contains("files.wordpress.com")) {
            Uri uri = Uri.parse(imageUrl.replace("&#038;", "&"));
            origWidth = StringUtils.stringToInt(uri.getQueryParameter("w"));
            origHeight = StringUtils.stringToInt(uri.getQueryParameter("h"));
        } else {
            return;
        }

        int newWidth;
        int newHeight;
        if (origWidth > 0 && origHeight > 0) {
            float ratio = ((float) origHeight / (float) origWidth);
            newWidth = mResourceVars.fullSizeImageWidth;
            newHeight = (int) (newWidth * ratio);
        } else if (origWidth > 0) {
            newWidth = mResourceVars.fullSizeImageWidth;
            newHeight = 0;
        } else {
            return;
        }

        String newImageUrl;
        if (mPost.isPrivate) {
            newImageUrl = ReaderUtils.getPrivateImageForDisplay(UrlUtils.removeQuery(imageUrl), newWidth, newHeight);
        } else {
            newImageUrl = PhotonUtils.getPhotonImageUrl(UrlUtils.removeQuery(imageUrl), newWidth, newHeight);
        }

        String newImageTag =
                String.format("<img class='size-full' src='%s' width='%d' height='%d' />",
                                                      newImageUrl, newWidth, newHeight);

        int start = mRenderBuilder.indexOf(imageTag);
        if (start == -1) {
            AppLog.w(AppLog.T.READER, "reader renderer > image not found in builder");
            return;
        }
        mRenderBuilder.replace(start, start + imageTag.length(), newImageTag);
    }

    /*
     * returns the basic content of the post tweaked for use here
     */
    static String getPostContent(ReaderPost post) {
        if (post == null) {
            return "";
        } else if (post.hasText()) {
            // some content (such as Vimeo embeds) don't have "http:" before links, correct this
            // here - note that we can't fix this by passing a baseUrl in renderContent because
            // that messages with webView's onPageFinished listener (no idea why)
            String content = post.getText().replace("src=\"//", "src=\"http://");
            if (post.hasFeaturedImage() && !PhotonUtils.isMshotsUrl(post.getFeaturedImage())) {
                // if the post has a featured image other than an mshot that's not in the content,
                // add it to the top of the content
                Uri uri = Uri.parse(post.getFeaturedImage());
                String path = StringUtils.notNullStr(uri.getLastPathSegment());
                if (!content.contains(path)) {
                    AppLog.d(AppLog.T.READER, "reader renderer > added featured image to content");
                    content = String.format("<p><img class='img.size-full' src='%s' /></p>", post.getFeaturedImage())
                            + content;
                }
            }
            return content;
        } else if (post.hasFeaturedImage()) {
            // some photo blogs have posts with empty content but still have a featured image, so
            // use the featured image as the content
            return String.format("<p><img class='img.size-full' src='%s' /></p>", post.getFeaturedImage());
        } else {
            return "";
        }
    }

    /*
     * returns the full content, including CSS, that will be shown in the WebView for this post
     */
    private String formatPostContentForWebView(String content) {
        StringBuilder sbHtml = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8' />");

        // title isn't strictly necessary, but source is invalid html5 without one
        sbHtml.append("<title>Reader Post</title>");

        // https://developers.google.com/chrome/mobile/docs/webview/pixelperfect
        sbHtml.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");

        // use "Open Sans" Google font
        sbHtml.append("<link rel='stylesheet' type='text/css' href='http://fonts.googleapis.com/css?family=Open+Sans' />");

        sbHtml.append("<style type='text/css'>")
              .append("  body { font-family: 'Open Sans', sans-serif; margin: 0px; padding: 0px;}")
              .append("  body, p, div { max-width: 100% !important; word-wrap: break-word; }")
              .append("  p, div { line-height: 1.6em; font-size: 1em; }")
              .append("  h1, h2 { line-height: 1.2em; }");

        // make sure long strings don't force the user to scroll horizontally
        sbHtml.append("  body, p, div, a { word-wrap: break-word; }");

        // use a consistent top/bottom margin for paragraphs, with no top margin for the first one
        sbHtml.append(String.format("  p { margin-top: %dpx; margin-bottom: %dpx; }",
               mResourceVars.marginSmall, mResourceVars.marginSmall))
               .append("    p:first-child { margin-top: 0px; }");

        // add background color and padding to pre blocks, and add overflow scrolling
        // so user can scroll the block if it's wider than the display
        sbHtml.append("  pre { overflow-x: scroll;")
              .append("        background-color: ").append(mResourceVars.greyExtraLightStr).append("; ")
              .append("        padding: ").append(mResourceVars.marginSmall).append("px; }");

        // add a left border to blockquotes
        sbHtml.append("  blockquote { margin-left: ").append(mResourceVars.marginSmall).append("px; ")
              .append("               padding-left: ").append(mResourceVars.marginSmall).append("px; ")
              .append("               border-left: 3px solid ").append(mResourceVars.greyLightStr).append("; }");

        // show links in the same color they are elsewhere in the app
        sbHtml.append("  a { text-decoration: none; color: ").append(mResourceVars.linkColorStr).append("; }");

        // if javascript is allowed, make sure embedded videos fit the browser width and
        // use 16:9 ratio (YouTube standard) - if not allowed, hide iframes/embeds
        if (canEnableJavaScript(mPost)) {
            sbHtml.append("  iframe, embed { width: ").append(mResourceVars.videoWidth).append("px !important;")
                  .append("                  height: ").append(mResourceVars.videoHeight).append("px !important; }");
        } else {
            sbHtml.append("  iframe, embed { display: none; }");
        }

        // don't allow any image to be wider than the viewport
        sbHtml.append("  img { max-width: 100% !important; height: auto; }");

        // light grey background for large images so something appears while they're loading, with a
        // small bottom margin
        sbHtml.append("  img.size-full, img.size-large { display: block;")
              .append("     background-color ").append(mResourceVars.greyExtraLightStr).append(";")
              .append("     margin-bottom: ").append(mResourceVars.marginSmall).append("px; }");

        // center medium-sized wp image
        sbHtml.append("  img.size-medium { display: block; margin-left: auto !important; margin-right: auto !important; }");

        // tiled image galleries look bad on mobile due to their hard-coded DIV and IMG sizes, so if
        // content contains a tiled image gallery, remove the height params and replace the width
        // params with ones that make images fit the width of the listView item, then adjust the
        // relevant CSS classes so their height/width are auto, and add top/bottom margin to images
        if (content.contains("tiled-gallery-item")) {
            String widthParam = "w=" + Integer.toString(mResourceVars.fullSizeImageWidth);
            content = content.replaceAll("w=[0-9]+", widthParam).replaceAll("h=[0-9]+", "");
            sbHtml.append("  div.gallery-row, div.gallery-group { width: auto !important; height: auto !important; }")
                  .append("  div.tiled-gallery-item img { ")
                  .append("     width: auto !important; height: auto !important;")
                  .append("     margin-top: ").append(mResourceVars.marginExtraSmall).append("px; ")
                  .append("     margin-bottom: ").append(mResourceVars.marginExtraSmall).append("px; ")
                  .append("  }")
                  .append("  div.tiled-gallery-caption { clear: both; }");
        }

        sbHtml.append("</style>");

        sbHtml.append("</head><body>")
              .append(content)
              .append("</body></html>");

        return sbHtml.toString();
    }

    /*
     * javascript should only be enabled for wp blogs (not external feeds)
     */
    static boolean canEnableJavaScript(ReaderPost post) {
        return (post != null && post.isWP());
    }
}
