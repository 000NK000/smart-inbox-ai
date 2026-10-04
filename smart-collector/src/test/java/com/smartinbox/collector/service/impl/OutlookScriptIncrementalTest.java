package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.collector.util.BridgeProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Execute the real script against an in-process fake COM object, never the user's Outlook. */
class OutlookScriptIncrementalTest {
    @TempDir Path directory;
    @Test void metadataAndSkippedMailNeverReadBodyProperties() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path script = Path.of("..", "scripts", "read-outlook-mail.ps1").toAbsolutePath().normalize();
        if (!Files.exists(script)) script = Path.of("scripts", "read-outlook-mail.ps1").toAbsolutePath().normalize();
        assertTrue(Files.exists(script));
        // Keep the real mail-reading script, but isolate its session boundary.
        // Never consult or launch the user's Outlook during a regression test.
        Path isolatedScript = directory.resolve("read-outlook-mail.ps1");
        Files.copy(script, isolatedScript);
        Files.writeString(directory.resolve("outlook-session.ps1"), """
            function Connect-OutlookSession {
                return @{ state = 'connected'; application = $global:fixtureApplication }
            }
            """);
        Path ids = directory.resolve("ids.json"); Files.writeString(ids, "[\"new\"]");
        String fixture = """
            param([string]$Script, [string]$Ids, [string]$Metadata)
            class FixtureItems : System.Collections.IEnumerable {
                [object[]]$Values
                FixtureItems([object[]]$items) { $this.Values = $items }
                [void] Sort([string]$field, [bool]$descending) { }
                [System.Collections.IEnumerator] GetEnumerator() { return $this.Values.GetEnumerator() }
            }
            $known = [pscustomobject]@{EntryID='known';MessageClass='IPM.Note';ReceivedTime=(Get-Date);PropertyAccessor=$null}
            $known | Add-Member ScriptProperty Body { throw 'Skipped Body was read' }
            $known | Add-Member ScriptProperty HTMLBody { throw 'Skipped HTMLBody was read' }
            $new = [pscustomobject]@{EntryID='new';MessageClass='IPM.Note';ReceivedTime=(Get-Date);PropertyAccessor=$null;Subject='Fixture';SenderEmailAddress='sender@example.test';SenderEmailType='SMTP';SenderName='Fixture';UnRead=$false}
            if ($Metadata -eq 'True') {
                $new | Add-Member ScriptProperty Body { throw 'Metadata Body was read' }
                $new | Add-Member ScriptProperty HTMLBody { throw 'Metadata HTMLBody was read' }
            } else {
                $new | Add-Member NoteProperty Body 'Complete fixture'
                $new | Add-Member NoteProperty HTMLBody '<p>Complete fixture</p>'
            }
            $global:fixtureInbox = [pscustomobject]@{Items=[FixtureItems]::new(@($known,$new))}
            $store = [pscustomobject]@{}
            $store | Add-Member ScriptMethod GetDefaultFolder { param($id) return $global:fixtureInbox }
            $global:fixtureSession = [pscustomobject]@{Offline=$false;Accounts=@([pscustomobject]@{SmtpAddress='fixture@example.test';DeliveryStore=$store})}
            $global:fixtureApplication = [pscustomobject]@{}
            $global:fixtureApplication | Add-Member ScriptMethod GetNamespace { param($name) return $global:fixtureSession }
            function New-Object {
                param([string]$ComObject,[Parameter(Position=0)][string]$TypeName,[Parameter(Position=1)][object[]]$ArgumentList)
                if ($ComObject) {
                    if ($ComObject -ne 'Outlook.Application') { throw 'Unexpected COM creation' }
                    return $global:fixtureApplication
                }
                Microsoft.PowerShell.Utility\\New-Object @PSBoundParameters
            }
            & $Script -Email 'fixture@example.test' -SinceIso ([DateTime]::UtcNow.AddHours(-120).ToString('o')) -MetadataOnly $Metadata -RequestedIdsFile $Ids
            """;
        Path runner = directory.resolve("fixture.ps1"); Files.writeString(runner, fixture);
        for (String metadata : new String[]{"True", "False"}) {
            var command = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", runner.toString(),
                    "-Script", isolatedScript.toString(), "-Ids", ids.toString(), "-Metadata", metadata);
            String output = BridgeProcess.capture(command, directory, Duration.ofSeconds(20));
            var json = new ObjectMapper().readTree(output);
            assertEquals("connected", json.path("state").asText(), output);
            assertEquals(metadata.equals("True") ? 2 : 1, json.path("messages").size());
            for (var mail : json.path("messages")) {
                if (metadata.equals("True")) { assertFalse(mail.has("body")); assertFalse(mail.has("htmlBody")); }
                else { assertEquals("new", mail.path("entryId").asText()); assertEquals("Complete fixture", mail.path("body").asText()); }
            }
        }
    }
}
