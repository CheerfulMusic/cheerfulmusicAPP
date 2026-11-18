"""Real MySQL regression, called only by the disconnected restore verifier.

The caller supplies an isolated mysql executor and the exact release SQL bytes.
No network connection or production database selection is supported here.
"""
import os
from pathlib import Path
import subprocess


def verify(execute, creation, repair):
    assert os.getuid() == 65534
    assert not Path('/etc/qingfeng-music').exists()
    assert Path('/proc/net/route').read_text().count('\n') == 1
    assert execute('SELECT @@skip_networking').strip() == b'1'
    cases = [('utf8mb4_unicode_ci', 'utf8mb4_0900_ai_ci'),
             ('utf8mb4_0900_ai_ci', 'utf8mb4_unicode_ci'),
             ('utf8mb4_unicode_ci', 'utf8mb4_unicode_ci')]
    for number, (legacy, default) in enumerate(cases):
        database = 'qf_annotation_compat_' + str(number)
        execute(f'CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE {default}')

        def sql(body):
            return execute('USE ' + database + ';\n' + body)

        sql(f'CREATE TABLE media_asset (id VARCHAR(36) PRIMARY KEY) '
            f'ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE {legacy}')
        sql("INSERT INTO media_asset VALUES ('00000000-0000-0000-0000-000000000001')")
        # Reproduce the originally shipped CREATE and retain an actual private note.
        sql("CREATE TABLE media_asset_annotation (asset_id VARCHAR(36) PRIMARY KEY, "
            "note VARCHAR(500) NOT NULL DEFAULT '', note_version BIGINT NOT NULL DEFAULT 0, "
            "deleted_at TIMESTAMP(6) NULL)")
        note = ('婚礼入场🎵\n' + '中' * 490).encode().hex()
        insert = ("INSERT INTO media_asset_annotation VALUES "
                  "('00000000-0000-0000-0000-000000000001',"
                  f"CONVERT(0x{note} USING utf8mb4),7,'2026-11-18 01:02:03.123456')")
        sql(insert)
        fields = 'SELECT HEX(asset_id),HEX(note),note_version,deleted_at FROM media_asset_annotation ORDER BY asset_id'
        before = sql(fields)
        join = 'SELECT COUNT(*) FROM media_asset m LEFT JOIN media_asset_annotation a ON a.asset_id=m.id'
        if legacy != default:
            try:
                sql(join)
            except subprocess.CalledProcessError as failure:
                assert b'1267' in failure.stderr, 'Expected collation error, got another failure'
            else:
                raise AssertionError('Mixed-collation regression did not reproduce')
        sql(repair.decode())
        assert sql(join).strip() == b'1'
        assert sql(fields) == before, 'Repair altered note, identifier, version or deletion timestamp'
        actual = sql("SELECT collation_name FROM information_schema.columns WHERE "
                     "table_schema=DATABASE() AND table_name='media_asset_annotation' "
                     "AND column_name='asset_id'").decode().strip()
        assert actual == legacy
        sql(repair.decode())
        sql(creation.decode())
        assert sql(fields) == before, 'Repeated execution altered existing data'
        # Only this newly created isolated fixture is removed, never restored business tables.
        sql('DROP TABLE media_asset_annotation')
        sql(creation.decode())
        assert sql(join).strip() == b'1'
        sql(insert)
        assert sql(fields) == before, 'Fresh schema did not preserve all fields'
    return {'cases': len(cases), 'mismatchReproduced': True, 'repairPreservedEveryField': True,
            'freshCreateAndRepeatVerified': True}
