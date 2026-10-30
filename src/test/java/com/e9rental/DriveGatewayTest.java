package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class DriveGatewayTest {
    GoogleTransport transport=mock(GoogleTransport.class);DriveOAuthService oauth=mock(DriveOAuthService.class);
    DriveGateway gateway=new DriveGateway(transport,oauth,new DriveExtractor(),GmailGatewayTest.sandbox());
    @BeforeEach void setup() throws Exception {
        when(transport.get(contains("/files/synthetic-folder?"),anyString())).thenReturn(SandboxBoundaryTest.root());
        when(transport.get(argThat(u->u!=null && u.matches(".*/files/[abc]\\?fields=.*")),anyString())).thenAnswer(call->{
            String url=call.getArgument(0);String id=url.substring(url.indexOf("/files/")+7,url.indexOf('?'));
            String mime=id.equals("a")?DriveExtractor.DOCS:id.equals("b")?DriveExtractor.SHEETS:"application/pdf";
            return DriveFixtures.file(id,mime,"Synthetic");
        });
        when(oauth.accessToken()).thenReturn("synthetic-drive-token");
        when(transport.get(contains("/about?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"user\":{\"permissionId\":\"synthetic-permission\"}}"));
    }
    @Test void listsOnlySupportedNonTrashedFilesAndStopsAtLimit() throws Exception {
        var page=Fixtures.JSON.createObjectNode();var files=page.putArray("files");
        files.add(DriveFixtures.file("f1",DriveExtractor.DOCS,"Office minutes"));
        files.add(DriveFixtures.file("f2",DriveExtractor.DOCX,"Sales"));page.put("nextPageToken","unused");
        when(transport.get(contains("/files?"),anyString())).thenReturn(page);
        assertThat(gateway.list(1).files()).hasSize(1);
        verify(transport).get(argThat(u->u.contains("trashed") && u.contains("false") && u.contains("mimeType")),eq("synthetic-drive-token"));
        verify(transport,never()).get(contains("pageToken="),anyString());
    }
    @Test void followsPaginationDeduplicatesAndHandlesEmptyDrive() throws Exception {
        var first=Fixtures.JSON.createObjectNode();first.putArray("files").add(DriveFixtures.file("f",DriveExtractor.DOCS,"Synthetic"));first.put("nextPageToken","p+2");
        var second=first.deepCopy();second.remove("nextPageToken");
        when(transport.get(contains("/files?"),anyString())).thenReturn(first,second);
        assertThat(gateway.list(5).files()).hasSize(1);
        verify(transport).get(contains("pageToken=p%2B2"),anyString());
        when(transport.get(contains("/files?"),anyString())).thenReturn(Fixtures.JSON.readTree("{}"));
        assertThat(gateway.list(5).files()).isEmpty();
    }
    @Test void rejectsRepeatedPageTokensAndInvalidLimit() throws Exception {
        when(transport.get(contains("/files?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"nextPageToken\":\"loop\"}"));
        assertThatThrownBy(()->gateway.list(5)).hasMessageContaining("Repeated");
        assertThatThrownBy(()->gateway.list(26)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void propagatesPermissionAndQuotaFailuresSafely() {
        when(transport.get(contains("/files?"),anyString())).thenThrow(new GmailFailure(403,"private ignored","RATE_LIMIT"));
        assertThatThrownBy(()->gateway.list(5)).isInstanceOf(GmailFailure.class).hasMessage("Drive metadata read failed");
    }
    @Test void exportsDocsAndAllSheetsAndDownloadsBinaryFiles() {
        when(transport.download(anyString(),anyString(),anyInt())).thenReturn(new byte[0]);
        gateway.content(DriveFixtures.file("a",DriveExtractor.DOCS,"Docs"));
        gateway.content(DriveFixtures.file("b",DriveExtractor.SHEETS,"Sheets"));
        gateway.content(DriveFixtures.file("c","application/pdf","PDF"));
        verify(transport).download(contains("/a/export?mimeType=text%2Fplain"),anyString(),eq(20*1024*1024));
        verify(transport).download(contains("spreadsheetml.sheet"),anyString(),anyInt());
        verify(transport).download(contains("/c?alt=media"),anyString(),anyInt());
    }
}
