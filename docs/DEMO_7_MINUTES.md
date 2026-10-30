# Seven-minute local demonstration

This is a timed walkthrough of the local practice build, not an organiser demo or live Google/Meta integration. Use only the generated synthetic fixtures. Do not display the token value, local log, runtime database or any private files. The committed connector outputs are provisional practice schemas; the organiser's access-guide schemas were not supplied.

## Prepare before starting the timer

From the repository root, in PowerShell:

```powershell
$demoRuntime = if ([string]::IsNullOrWhiteSpace($env:E9RENTAL_DEMO_RUNTIME)) { Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/demo' } else { $env:E9RENTAL_DEMO_RUNTIME }
mvn -B clean verify
./scripts/Verify-PublicOutputs.ps1
./scripts/Start-InterpretationSynthetic.ps1 -Port 8082 -PrivateDirectory $demoRuntime
```

To use another private runtime directory, set `$env:E9RENTAL_DEMO_RUNTIME` in each PowerShell terminal before running the commands. The default uses the current Windows user's LocalAppData directory and remains outside the repository.

Keep the backend running in this terminal. In a second PowerShell terminal:

```powershell
$demoRuntime = if ([string]::IsNullOrWhiteSpace($env:E9RENTAL_DEMO_RUNTIME)) { Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/demo' } else { $env:E9RENTAL_DEMO_RUNTIME }
./scripts/Verify-InterpretationSynthetic.ps1 -Port 8082 -PrivateDirectory $demoRuntime -SkipIngestion
Set-Location frontend
$env:BACKEND_ORIGIN='http://127.0.0.1:8082'
npm run dev -- --port 5174
```

Open `http://127.0.0.1:5174`. In a local editor, open `Join-Path $demoRuntime 'local-api-token.key'` and enter its value directly in the local form. Do not paste or read it aloud. Ensure the dashboard says the backend is online and visibly labels WhatsApp simulated and the records synthetic. The current packaged reasoning runtime has 17 indexed records; confirm the count from the verifier immediately before the timed demo. The imported sources come from the committed provisional practice outputs; the count can differ from the default 15-record USD 1200 demo because the fixture snapshot is different.

## Timed walkthrough

| Elapsed | Show | Evidence/expected result |
| --- | --- | --- |
| 0:00–0:45 | Dashboard and source labels | Gmail, Drive and simulated WhatsApp are separate; practice data is marked synthetic. Explain that no live account is connected. |
| 0:45–1:30 | Three committed `outputs/*_output.json` files | `Verify-PublicOutputs.ps1` passed before the timer. Show nine Gmail messages, five flagged attachment examples/reasons, five Drive categories, and eight simulated WhatsApp messages/two synthetic groups. State that schemas are provisional. |
| 1:30–2:00 | Protected local app and shared index | Show health `UP` and the actual `indexed_records` value returned by the local synthetic verifier. Authentication is a local single-user bearer, not production login. |
| 2:00–3:05 | Ask “What were our sales last month?” | Deterministic answer: USD 1500 from explicit synthetic invoices `sale-a` and `sale-doc-0`. Open both citations and source excerpts. The answer applies only to the reviewed fixture snapshot. |
| 3:05–4:00 | Ask “Which sales emails are still pending a reply?” | Show `pending-boundary` and `pending-old`, their Gmail citations and the configured five-day reference cutoff. Explain that the indexed sample does not establish a complete mailbox. |
| 4:00–5:00 | Ask “What did the site team agree on in the WhatsApp group this week?” | Show the crane-delivery and safety-inspection messages, each linked to `sim-agree-1` or `sim-agree-2`. Repeat “What did the site team decide this week?” to show the bounded paraphrase. |
| 5:00–5:45 | Ask “What is our profit margin?” | The app abstains with no citations because the records do not contain the required evidence. |
| 5:45–6:30 | Open source metadata and citations | Show source type, identifier, locator and exact excerpt. Note that deterministic rules validate the supporting record; no external LLM is configured. |
| 6:30–7:00 | State limitations and stop | Gmail/Drive sandbox access, official Meta access, participant contract and official output schemas remain unavailable. Do not call the demo live or claim hidden-question coverage. Stop the timer at seven minutes and record actual start/end times. |

## Verification commands

Before or after the timed presentation, run:

```powershell
$demoRuntime = if ([string]::IsNullOrWhiteSpace($env:E9RENTAL_DEMO_RUNTIME)) { Join-Path $env:LOCALAPPDATA 'E9RentalConnectorPractice/demo' } else { $env:E9RENTAL_DEMO_RUNTIME }
./scripts/Verify-ReasoningSynthetic.ps1 -Port 8082 -PrivateDirectory $demoRuntime -SkipIngestion
./scripts/Generate-PublicOutputs.ps1 -Check
./scripts/Verify-PublicOutputs.ps1
```

These checks do not replace the timed demonstration. Record their actual results and the demonstration's measured duration in `TEST_RESULTS.md` and `PROJECT_TRACKER.md`. If a scenario fails, stop the demo and report the observed result; do not edit data or invent an answer to make the demonstration pass.
