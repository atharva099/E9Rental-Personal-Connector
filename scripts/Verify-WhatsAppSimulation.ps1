param([string]$PrivateDirectory='D:\E9Rental-Secrets\gmail-runtime',
      [string]$OutputFile='')
$ErrorActionPreference='Stop'
try{
    $root=Split-Path $PSScriptRoot -Parent
    if(-not $OutputFile){$OutputFile=Join-Path $PrivateDirectory 'outputs/whatsapp_simulated_output.json'}
    $text=[IO.File]::ReadAllText($OutputFile)
    if(-not(Test-Json -Json $text -SchemaFile (Join-Path $root 'docs/whatsapp-simulated-output.schema.json') -ErrorAction Stop)){throw 'Schema invalid'}
    if($text -match 'EXCLUDED_SENTINEL|CONFLICT_SENTINEL|INVALID_SENTINEL'){throw 'Rejected content appeared in output'}
    $output=$text|ConvertFrom-Json -Depth 100
    if($output.message_count -ne @($output.messages).Count){throw 'Count mismatch'}
    $identities=[Collections.Generic.HashSet[string]]::new();$messages=@{}
    foreach($record in $output.messages){
        $source=$record.source
        if(-not $identities.Add($source.document_id) -or $source.parent_external_id -notin $output.selected_groups -or
            $source.metadata.group_id -ne $source.parent_external_id -or $record.citation.document_id -ne $source.document_id -or
            $record.citation.source_version -ne $source.source_version){throw 'Source mismatch'}
        $sha=[Security.Cryptography.SHA256]::Create()
        try{$hash=[Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($source.content_text))).ToLowerInvariant()}finally{$sha.Dispose()}
        if($hash -ne $source.content_hash){throw 'Hash mismatch'}
        $messages[$source.parent_external_id+'|'+$source.external_id]=$source.content_text
    }
    $summaries=0;$commitments=0;$actions=0
    foreach($group in $output.groups){
        if($group.group_id -notin $output.selected_groups){throw 'Unselected group exported'}
        $groupMessages=@($output.messages|Where-Object{$_.source.parent_external_id -eq $group.group_id})
        if($group.message_count -ne $groupMessages.Count){throw 'Group count mismatch'}
        foreach($evidence in @($group.summary)+@($group.commitments)+@($group.action_items)){
            foreach($id in $evidence.supporting_message_ids){
                $key=$group.group_id+'|'+$id
                if(-not $messages.ContainsKey($key) -or $evidence.start_offset -lt 0 -or $evidence.end_offset -gt $messages[$key].Length -or $messages[$key].Substring($evidence.start_offset,$evidence.end_offset-$evidence.start_offset) -cne $evidence.text){throw 'Unsupported claim'}
            }
        }
        $summaries+=@($group.summary).Count;$commitments+=@($group.commitments).Count;$actions+=@($group.action_items).Count
    }
    $log=Join-Path $PrivateDirectory 'whatsapp-simulated/application.log'
    if((Test-Path -LiteralPath $log) -and (Select-String -LiteralPath $log -Pattern 'EXCLUDED_SENTINEL','CONFLICT_SENTINEL','INVALID_SENTINEL' -SimpleMatch -Quiet)){throw 'Rejected content appeared in logs'}
    [pscustomobject]@{status='VALIDATED';data_mode='SIMULATED';live_integration=$false;message_count=$output.message_count;
        group_count=@($output.groups).Count;summary_items=$summaries;commitments=$commitments;action_items=$actions;
        schema_valid=$true;source_references_valid=$true;rejected_content_absent=$true}
}catch{throw 'Simulation verification failed; payload and validation details withheld.'}
