package com.e9rental.connector;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.model.*;
import com.e9rental.service.GmailAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.math.BigInteger;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
/** Local single-user search index. SQL predicates apply before private JSON is decoded. */
@Repository
public class UnifiedRepository {
    public record Result(int inserted,int updated,int unchanged,int removed) {}
    public record SyncBatchResult(boolean replayed,String historyId,Result records) {}
    public record Query(String keyword,String source,String classification,String account,String participant,
            String group,String mode,Instant from,Instant to,int limit,int offset) {
        public Query {
            if(limit<1 || limit>100 || offset<0 || offset>10000 ||
                (keyword!=null && keyword.length()>256) ||
                (source!=null && !Set.of("GMAIL","DRIVE","WHATSAPP").contains(source)) ||
                (mode!=null && !Set.of("LIVE","SYNTHETIC","SIMULATED").contains(mode)) ||
                (from!=null && to!=null && from.isAfter(to)))throw new IllegalArgumentException();
        }
    }
    private static final Duration PERSONAL_GMAIL_RETENTION=Duration.ofDays(30);
    private static final int PERSONAL_GMAIL_PURGE_BATCH_SIZE=100;
    private final String url;private final ObjectMapper json;private final WhatsAppPolicy policy;private final GoogleSandboxPolicy sandbox;private final Clock clock;
    public UnifiedRepository(String url,ObjectMapper json,WhatsAppPolicy policy){this(url,json,policy,GoogleSandboxPolicy.denied(),Clock.systemUTC());}
    public UnifiedRepository(String url,ObjectMapper json,WhatsAppPolicy policy,GoogleSandboxPolicy sandbox){this(url,json,policy,sandbox,Clock.systemUTC());}
    @org.springframework.beans.factory.annotation.Autowired
    public UnifiedRepository(@Value("${UNIFIED_STORE_URL:jdbc:h2:file:D:/E9Rental-Secrets/gmail-runtime/unified/index;DB_CLOSE_ON_EXIT=FALSE}") String url,
            ObjectMapper json,WhatsAppPolicy policy,GoogleSandboxPolicy sandbox,Clock clock) {
        if(!(url.startsWith("jdbc:h2:file:") || url.startsWith("jdbc:h2:mem:")) || url.toUpperCase(Locale.ROOT).contains("INIT="))
            throw new IllegalArgumentException("Only embedded local index storage is supported");
        this.url=url+";TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0;WRITE_DELAY=0";this.json=json;this.policy=policy;this.sandbox=sandbox;this.clock=clock;
    }
    private Connection open() throws SQLException {
        var db=DriverManager.getConnection(url,"sa","");
        try { ensureSchema(db);if(sandbox.personalMode())ensureGmailTombstoneSchema(db); }
        catch(SQLException e){db.close();throw e;}
        return db;
    }
    /** Create the empty unified schema for the explicitly approved offline bootstrap only. */
    public static void initializeEmptySchema(String jdbcUrl) throws SQLException {
        if(jdbcUrl==null || !jdbcUrl.startsWith("jdbc:h2:file:") || jdbcUrl.toUpperCase(Locale.ROOT).contains("INIT=") ||
                jdbcUrl.toUpperCase(Locale.ROOT).contains("IFEXISTS=")) throw new IllegalArgumentException("Unsafe bootstrap database URL");
        String location=jdbcUrl.substring("jdbc:h2:file:".length()).split(";",2)[0];
        java.nio.file.Path database=java.nio.file.Path.of(location).toAbsolutePath().normalize();
        if(java.nio.file.Files.exists(java.nio.file.Path.of(database+".mv.db"),java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                java.nio.file.Files.exists(java.nio.file.Path.of(database+".lock.db"),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Bootstrap database already exists");
        try { java.nio.file.Files.createDirectories(database.getParent()); }
        catch(java.io.IOException e) { throw new SQLException("Cannot prepare isolated database directory",e); }
        try(var db=DriverManager.getConnection(jdbcUrl+";TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0;WRITE_DELAY=0","sa","")) {
            ensureSchema(db);
            try(var s=db.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM unified_records")) {
                if(!rows.next() || rows.getLong(1)!=0) throw new IllegalStateException("Bootstrap database is not empty");
            }
        }
    }
    private static void ensureSchema(Connection db) throws SQLException {
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS unified_records (identity_key VARCHAR(64) PRIMARY KEY,tenant VARCHAR(100),account VARCHAR(128),source VARCHAR(16),group_id VARCHAR(128),mode VARCHAR(16),category VARCHAR(64),participant VARCHAR(1024),event_at TIMESTAMP WITH TIME ZONE,search_text CLOB,fingerprint VARCHAR(64),record_json CLOB)");
            // Nullable additions preserve pre-existing rows as legacy, non-expiring records.
            s.execute("ALTER TABLE unified_records ADD COLUMN IF NOT EXISTS first_ingested_at TIMESTAMP WITH TIME ZONE");
            s.execute("ALTER TABLE unified_records ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE");
            s.execute("CREATE INDEX IF NOT EXISTS unified_scope ON unified_records(tenant,source,account)");
            s.execute("CREATE INDEX IF NOT EXISTS unified_gmail_expiry ON unified_records(tenant,account,source,mode,expires_at,identity_key)");
        }
    }
    private static void ensureGmailSyncSchema(Connection db) throws SQLException {
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS gmail_sync_checkpoints (tenant VARCHAR(100) NOT NULL,account VARCHAR(128) NOT NULL,history_id VARCHAR(128) NOT NULL,last_batch_start VARCHAR(128),last_batch_end VARCHAR(128) NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(tenant,account))");
        }
    }
    private static void ensureGmailTombstoneSchema(Connection db) throws SQLException {
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS gmail_deleted_tombstones (tenant VARCHAR(100) NOT NULL,account VARCHAR(128) NOT NULL,identity_key VARCHAR(64) NOT NULL,deleted_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(tenant,account,identity_key))");
        }
    }
    private String visible(List<Object> args) {
        args.add(policy.tenant());
        String sql="tenant=? AND (expires_at IS NULL OR expires_at>?) AND "+(sandbox.personalMode()?"mode='LIVE' AND ":"")+"(mode<>'LIVE' OR (source='GMAIL' AND ";
        args.add(clock.instant().atOffset(java.time.ZoneOffset.UTC));
        if(sandbox.gmailConfigured()){sql+="account=?";args.add(sandbox.gmailRecordAccount());}else sql+="1=0";
        sql+=") OR (source='DRIVE' AND ";
        if(sandbox.driveConfigured()){sql+="account=? AND group_id=?";args.add(sandbox.driveRecordAccount());args.add(sandbox.root());}else sql+="1=0";
        sql+=")) AND (source<>'WHATSAPP' OR (mode='SIMULATED' AND account=? AND ";
        args.add(policy.account());
        if(policy.selectedGroups().isEmpty())return sql+"1=0))";
        sql+="group_id IN ("+String.join(",",Collections.nCopies(policy.selectedGroups().size(),"?"))+")))";
        args.addAll(policy.selectedGroups());return sql;
    }
    private void bind(PreparedStatement s,List<Object> args) throws SQLException {
        for(int i=0;i<args.size();i++)s.setObject(i+1,args.get(i));
    }
    private String gmailIdentity(String tenant,String account,String messageId) {
        return GmailAnalyzer.hash(tenant+"\u0000"+account+"\u0000GMAIL\u0000"+messageId);
    }
    private boolean personalLiveGmail(SearchRecord record) {
        return sandbox.personalMode() && "GMAIL".equals(record.source().sourceType()) && "LIVE".equals(record.dataMode());
    }
    private boolean gmailTombstoned(Connection db,String tenant,String account,String identity) throws SQLException {
        try(var q=db.prepareStatement("SELECT 1 FROM gmail_deleted_tombstones WHERE tenant=? AND account=? AND identity_key=?")) {
            q.setString(1,tenant);q.setString(2,account);q.setString(3,identity);
            try(var rows=q.executeQuery()){return rows.next();}
        }
    }
    private void addGmailTombstone(Connection db,String tenant,String account,String identity) throws SQLException {
        try(var q=db.prepareStatement("MERGE INTO gmail_deleted_tombstones KEY(tenant,account,identity_key) VALUES(?,?,?,?)")) {
            q.setString(1,tenant);q.setString(2,account);q.setString(3,identity);q.setObject(4,clock.instant().atOffset(java.time.ZoneOffset.UTC));q.executeUpdate();
        }
    }
    private int removeGmailRecord(Connection db,String tenant,String account,String identity) throws SQLException {
        try(var q=db.prepareStatement("DELETE FROM unified_records WHERE identity_key=? AND tenant=? AND account=? AND source='GMAIL'")) {
            q.setString(1,identity);q.setString(2,tenant);q.setString(3,account);return q.executeUpdate();
        }
    }
    /** Purges one bounded batch of expired Personal LIVE Gmail rows and tombstones each identity atomically. */
    private int purgeExpiredPersonalGmail(Connection db) throws SQLException {
        if(!sandbox.personalMode() || !sandbox.gmailConfigured())return 0;
        String tenant=policy.tenant(),account=sandbox.gmailRecordAccount();
        OffsetDateTime cutoff=clock.instant().atOffset(java.time.ZoneOffset.UTC);
        List<String> identities=new ArrayList<>(PERSONAL_GMAIL_PURGE_BATCH_SIZE);
        try(var q=db.prepareStatement("SELECT identity_key FROM unified_records WHERE tenant=? AND account=? AND source='GMAIL' AND mode='LIVE' AND expires_at IS NOT NULL AND expires_at<=? ORDER BY expires_at,identity_key FETCH FIRST 100 ROWS ONLY FOR UPDATE")) {
            q.setString(1,tenant);q.setString(2,account);q.setObject(3,cutoff);
            try(var rows=q.executeQuery()){while(rows.next())identities.add(rows.getString(1));}
        }
        int purged=0;
        for(String identity:identities) {
            try(var q=db.prepareStatement("DELETE FROM unified_records WHERE identity_key=? AND tenant=? AND account=? AND source='GMAIL' AND mode='LIVE' AND expires_at IS NOT NULL AND expires_at<=?")) {
                q.setString(1,identity);q.setString(2,tenant);q.setString(3,account);q.setObject(4,cutoff);
                if(q.executeUpdate()==1) {
                    addGmailTombstone(db,tenant,account,identity);
                    purged++;
                }
            }
        }
        return purged;
    }
    /** Object/map key order is not evidence; keep array order and all substantive values. */
    private com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode value) {
        if(value.isObject()) {
            var sorted=json.createObjectNode();var fields=new java.util.TreeSet<String>();value.fieldNames().forEachRemaining(fields::add);
            for(String field:fields)sorted.set(field,canonical(value.get(field)));return sorted;
        }
        if(value.isArray()) {var array=json.createArrayNode();value.forEach(item->array.add(canonical(item)));return array;}
        return value;
    }
    public synchronized Result upsert(List<SearchRecord> records) {
        for(var record:records)sandbox.checkRecord(record);
        try(var db=open()) {
            db.setAutoCommit(false);
            try {
                Result result=upsert(db,records,true);
                db.commit();return result;
            }catch(Exception e){db.rollback();throw e;}
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new GmailFailure(503,"Unified persistence unavailable");}
    }
    private Result upsert(Connection db,List<SearchRecord> records,boolean pruneWhatsApp) throws Exception {
        int inserted=0,updated=0,unchanged=0,removed=0;
        removed+=purgeExpiredPersonalGmail(db);
        if(pruneWhatsApp) {
            List<Object> permitted=new ArrayList<>();String visible=visible(permitted);
            try(var prune=db.prepareStatement("DELETE FROM unified_records WHERE tenant=? AND source='WHATSAPP' AND NOT ("+visible+")")) {
                List<Object> args=new ArrayList<>();args.add(policy.tenant());args.addAll(permitted);bind(prune,args);removed+=prune.executeUpdate();
            }
        }
        for(var r:records) {
            var s=r.source();
            if(!policy.tenant().equals(s.tenantId()) || !Set.of("GMAIL","DRIVE","WHATSAPP").contains(s.sourceType()))throw new IllegalArgumentException();
            if("WHATSAPP".equals(s.sourceType()) &&
               (!"SIMULATED".equals(r.dataMode()) || !policy.account().equals(s.connectorAccountId()) || !policy.selected(s.parentExternalId())))
                throw new IllegalArgumentException();
            String identity="GMAIL".equals(s.sourceType())?gmailIdentity(s.tenantId(),s.connectorAccountId(),s.externalId()):
                GmailAnalyzer.hash(s.tenantId()+"\u0000"+s.connectorAccountId()+"\u0000"+s.sourceType()+"\u0000"+
                    ("WHATSAPP".equals(s.sourceType())?s.parentExternalId()+"\u0000":"")+s.externalId());
            if(personalLiveGmail(r) && gmailTombstoned(db,s.tenantId(),s.connectorAccountId(),identity)){unchanged++;continue;}
            String payload=json.writeValueAsString(r);
            // Receipt/sync times can change on an identical export; evidence/version and substantive metadata determine updates.
            var tree=json.valueToTree(r);
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("source")).remove(List.of("ingested_at","last_synced_at"));
            String fingerprint=GmailAnalyzer.hash(canonical(tree).toString());
            String previous=null;OffsetDateTime firstIngested=null,expiresAt=null;
            try(var q=db.prepareStatement("SELECT fingerprint,first_ingested_at,expires_at FROM unified_records WHERE identity_key=?")) {
                q.setString(1,identity);try(var rows=q.executeQuery()){if(rows.next()){
                    previous=rows.getString(1);firstIngested=rows.getObject(2,OffsetDateTime.class);expiresAt=rows.getObject(3,OffsetDateTime.class);
                }}
            }
            // Keep expired identities as replay guards. An older export cannot replace or revive them.
            if(expiresAt!=null && !expiresAt.toInstant().isAfter(clock.instant())){unchanged++;continue;}
            if(s.deletedAt()!=null) {
                if(personalLiveGmail(r)) {
                    addGmailTombstone(db,s.tenantId(),s.connectorAccountId(),identity);
                    removed+=removeGmailRecord(db,s.tenantId(),s.connectorAccountId(),identity);
                } else {
                    try(var d=db.prepareStatement("DELETE FROM unified_records WHERE identity_key=?")){d.setString(1,identity);removed+=d.executeUpdate();}
                }
                continue;
            }
            if(fingerprint.equals(previous)){unchanged++;continue;}
            if(previous==null){
                firstIngested=clock.instant().atOffset(java.time.ZoneOffset.UTC);
                if(sandbox.personalMode() && "GMAIL".equals(s.sourceType()) && "LIVE".equals(r.dataMode()))
                    expiresAt=firstIngested.plus(PERSONAL_GMAIL_RETENTION);
            }
            try(var q=db.prepareStatement("MERGE INTO unified_records KEY(identity_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                q.setString(1,identity);q.setString(2,s.tenantId());q.setString(3,s.connectorAccountId());q.setString(4,s.sourceType());
                q.setString(5,s.parentExternalId());q.setString(6,r.dataMode());q.setString(7,r.classification());q.setString(8,r.participant());
                q.setObject(9,s.occurredAt()==null?null:s.occurredAt().atOffset(java.time.ZoneOffset.UTC));
                q.setString(10,(Objects.toString(s.title(),"")+"\n"+s.contentText()).toLowerCase(Locale.ROOT));
                q.setString(11,fingerprint);q.setString(12,payload);q.setObject(13,firstIngested);q.setObject(14,expiresAt);q.executeUpdate();
            }
            if(previous==null)inserted++;else updated++;
        }
        return new Result(inserted,updated,unchanged,removed);
    }

    /** Atomically applies a synthetic/provider-independent history batch and its durable checkpoint. */
    public synchronized SyncBatchResult applyGmailSyncBatch(String startHistoryId,String endHistoryId,List<SearchRecord> records) {
        return applyGmailSyncBatch(startHistoryId,endHistoryId,records,List.of());
    }

    /** Applies changed and removed message identities plus the durable cursor atomically. */
    public synchronized SyncBatchResult applyGmailSyncBatch(String startHistoryId,String endHistoryId,List<SearchRecord> records,List<String> removedMessageIds) {
        if(!sandbox.personalMode())throw new IllegalStateException("Personal Gmail sync checkpoints require Personal mode");
        sandbox.requireGmail();
        if(!historyId(endHistoryId) || (startHistoryId!=null && !historyId(startHistoryId)))
            throw new IllegalArgumentException("Invalid Gmail history transition");
        if(records==null || removedMessageIds==null)throw new IllegalArgumentException("Invalid Gmail history batch");
        Set<String> messageIds=new HashSet<>();
        for(var r:records) {
            if(r==null || r.source()==null)throw new IllegalArgumentException("Invalid Gmail history batch");
            sandbox.checkRecord(r);
            if(!"GMAIL".equals(r.source().sourceType()) || !"LIVE".equals(r.dataMode()) ||
                    !policy.tenant().equals(r.source().tenantId()) || !sandbox.gmailRecordAccount().equals(r.source().connectorAccountId()) ||
                    r.source().deletedAt()!=null || r.source().externalId()==null || !messageIds.add(r.source().externalId()))
                throw new IllegalArgumentException("Invalid Gmail history batch");
        }
        Set<String> removalIds=new HashSet<>();
        for(String id:removedMessageIds) {
            if(id==null || id.isBlank() || id.length()>512 || id.chars().anyMatch(Character::isISOControl) ||
                    !removalIds.add(id) || messageIds.contains(id))throw new IllegalArgumentException("Invalid Gmail history batch");
        }
        try(var db=open()) {
            ensureGmailSyncSchema(db);
            db.setAutoCommit(false);
            try {
                String current=null,lastStart=null,lastEnd=null;
                try(var q=db.prepareStatement("SELECT history_id,last_batch_start,last_batch_end FROM gmail_sync_checkpoints WHERE tenant=? AND account=? FOR UPDATE")) {
                    q.setString(1,policy.tenant());q.setString(2,sandbox.gmailRecordAccount());
                    try(var row=q.executeQuery()){if(row.next()){current=row.getString(1);lastStart=row.getString(2);lastEnd=row.getString(3);}}
                }
                if(current!=null && current.equals(endHistoryId) && Objects.equals(lastStart,startHistoryId) && Objects.equals(lastEnd,endHistoryId)) {
                    db.rollback();return new SyncBatchResult(true,current,new Result(0,0,records.size(),0));
                }
                if(startHistoryId!=null && new BigInteger(endHistoryId).compareTo(new BigInteger(startHistoryId))<=0)
                    throw new IllegalArgumentException("Gmail history cursor must advance");
                if((current==null && startHistoryId!=null) || (current!=null && !current.equals(startHistoryId)))
                    throw new IllegalArgumentException("Gmail history checkpoint does not match batch start");
                int removed=0;
                for(String messageId:removedMessageIds) {
                    String identity=gmailIdentity(policy.tenant(),sandbox.gmailRecordAccount(),messageId);
                    addGmailTombstone(db,policy.tenant(),sandbox.gmailRecordAccount(),identity);
                    removed+=removeGmailRecord(db,policy.tenant(),sandbox.gmailRecordAccount(),identity);
                }
                Result applied=upsert(db,records,false);
                Result changes=new Result(applied.inserted(),applied.updated(),applied.unchanged(),applied.removed()+removed);
                OffsetDateTime updated=clock.instant().atOffset(java.time.ZoneOffset.UTC);
                if(current==null) {
                    try(var q=db.prepareStatement("INSERT INTO gmail_sync_checkpoints(tenant,account,history_id,last_batch_start,last_batch_end,updated_at) VALUES(?,?,?,?,?,?)")) {
                        q.setString(1,policy.tenant());q.setString(2,sandbox.gmailRecordAccount());q.setString(3,endHistoryId);
                        q.setString(4,startHistoryId);q.setString(5,endHistoryId);q.setObject(6,updated);q.executeUpdate();
                    }
                } else {
                    try(var q=db.prepareStatement("UPDATE gmail_sync_checkpoints SET history_id=?,last_batch_start=?,last_batch_end=?,updated_at=? WHERE tenant=? AND account=? AND history_id=?")) {
                        q.setString(1,endHistoryId);q.setString(2,startHistoryId);q.setString(3,endHistoryId);q.setObject(4,updated);
                        q.setString(5,policy.tenant());q.setString(6,sandbox.gmailRecordAccount());q.setString(7,current);
                        if(q.executeUpdate()!=1)throw new IllegalStateException("Gmail history checkpoint changed concurrently");
                    }
                }
                db.commit();return new SyncBatchResult(false,endHistoryId,changes);
            } catch(Exception e) { db.rollback();throw e; }
        } catch(IllegalArgumentException | GmailFailure | IllegalStateException e) { throw e; }
        catch(Exception e) { throw new GmailFailure(503,"Gmail sync batch could not be committed"); }
    }

    /** User-requested deletion is durable across stale export ingestion and scoped to this Personal identity. */
    public synchronized boolean deletePersonalGmailRecord(String messageId) {
        if(!sandbox.personalMode())throw new IllegalStateException("Personal Gmail deletion requires Personal mode");
        sandbox.requireGmail();
        if(messageId==null || messageId.isBlank() || messageId.length()>512 || messageId.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Gmail record identity");
        String identity=gmailIdentity(policy.tenant(),sandbox.gmailRecordAccount(),messageId);
        try(var db=open()) {
            ensureGmailSyncSchema(db);db.setAutoCommit(false);
            try {
                addGmailTombstone(db,policy.tenant(),sandbox.gmailRecordAccount(),identity);
                boolean removed=removeGmailRecord(db,policy.tenant(),sandbox.gmailRecordAccount(),identity)>0;
                db.commit();return removed;
            } catch(Exception e){db.rollback();throw e;}
        } catch(IllegalArgumentException | IllegalStateException e){throw e;}
        catch(Exception e){throw new GmailFailure(503,"Gmail deletion could not be committed");}
    }

    private boolean historyId(String value) { return value!=null && value.matches("[0-9]{1,128}"); }

    /** Returns the cursor for this repository's bound tenant/account only; never logs or exposes it over HTTP. */
    public synchronized Optional<String> gmailSyncHistoryId() {
        if(!sandbox.personalMode())throw new IllegalStateException("Personal Gmail sync checkpoints require Personal mode");
        sandbox.requireGmail();
        try(var db=open()) {
            ensureGmailSyncSchema(db);
            try(var q=db.prepareStatement("SELECT history_id FROM gmail_sync_checkpoints WHERE tenant=? AND account=?")) {
                q.setString(1,policy.tenant());q.setString(2,sandbox.gmailRecordAccount());
                try(var rows=q.executeQuery()){return rows.next()?Optional.of(rows.getString(1)):Optional.empty();}
            }
        } catch(Exception e) { throw new GmailFailure(503,"Gmail sync checkpoint unavailable"); }
    }

    /** Checks a Personal Gmail tombstone before sync fetches any message content. */
    public synchronized boolean isPersonalGmailTombstoned(String messageId) {
        if(!sandbox.personalMode())throw new IllegalStateException("Personal Gmail sync requires Personal mode");
        sandbox.requireGmail();
        if(messageId==null || messageId.isBlank() || messageId.length()>512 || messageId.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Gmail record identity");
        String identity=gmailIdentity(policy.tenant(),sandbox.gmailRecordAccount(),messageId);
        try(var db=open()) {
            ensureGmailSyncSchema(db);
            return gmailTombstoned(db,policy.tenant(),sandbox.gmailRecordAccount(),identity);
        } catch(Exception e) { throw new GmailFailure(503,"Gmail tombstone state unavailable"); }
    }
    public synchronized List<SearchRecord> search(Query query) {
        List<Object> args=new ArrayList<>();String sql="SELECT record_json FROM unified_records WHERE "+visible(args);
        String[] columns={"source","category","account","participant","group_id","mode"};
        String[] values={query.source(),query.classification(),query.account(),query.participant(),query.group(),query.mode()};
        for(int i=0;i<values.length;i++)if(values[i]!=null){sql+=" AND "+columns[i]+"=?";args.add(values[i]);}
        if(query.keyword()!=null && !query.keyword().isBlank()) {
            sql+=" AND search_text LIKE ? ESCAPE '!'";
            args.add("%"+query.keyword().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        if(query.from()!=null){sql+=" AND event_at>=?";args.add(query.from().atOffset(java.time.ZoneOffset.UTC));}
        if(query.to()!=null){sql+=" AND event_at<=?";args.add(query.to().atOffset(java.time.ZoneOffset.UTC));}
        sql+=" ORDER BY event_at DESC NULLS LAST,identity_key LIMIT ? OFFSET ?";args.add(query.limit());args.add(query.offset());
        try(var db=open();var q=db.prepareStatement(sql)) {
            bind(q,args);List<SearchRecord> result=new ArrayList<>();
            try(var rows=q.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),SearchRecord.class));}
            return List.copyOf(result);
        }catch(Exception e){throw new GmailFailure(503,"Unified search unavailable");}
    }
}
