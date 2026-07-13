package com.osrsai;

import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Test;

public class OsrsAiPanelTest {
    @Test
    public void formatsHeadingsListsAndEscaping() throws Exception {
        OsrsAiPanel panel = new OsrsAiPanel(null);
        Method formatter = OsrsAiPanel.class.getDeclaredMethod("formatMarkdownToHtml", String.class);
        formatter.setAccessible(true);

        String input = "### Player Profile\n- Name: tim\n- **Combat**: 91\n\nSafe <tag>";
        String html = (String) formatter.invoke(panel, input);

        Assert.assertTrue(html.contains("font-weight:bold"));
        Assert.assertTrue(html.contains("<ul"));
        Assert.assertTrue(html.contains("<li>Name: tim</li>"));
        Assert.assertTrue(html.contains("<li><b>Combat</b>: 91</li>"));
        Assert.assertTrue(html.contains("Safe &lt;tag&gt;"));
    }
}
