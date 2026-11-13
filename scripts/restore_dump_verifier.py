"""Strict comparison of canonical single-row mysqldump exports; no database access."""
import hashlib
import re


class VerificationError(ValueError):
    pass


def require(value, code):
    if not value:
        raise VerificationError(code)


def sha(value):
    return hashlib.sha256(value).hexdigest()


# Only a column's explicit utf8mb4 charset immediately before its utf8mb4
# collation is redundant. Do not normalize values, defaults, comments or names.
COLUMN = re.compile(rb'^(  `[A-Za-z0-9_]+` (?:varchar\([0-9]+\)|char\([0-9]+\)|tinytext|text|mediumtext|longtext))(?: CHARACTER SET utf8mb4)?( COLLATE utf8mb4_[a-z0-9_]+(?:[ ,].*)?)$')
CREATE = re.compile(rb'^CREATE TABLE `([A-Za-z0-9_]+)` \($')
INSERT = re.compile(rb'^INSERT INTO `([A-Za-z0-9_]+)` VALUES \(')


def inspect(raw):
    require(raw.endswith(b'\n'), 'INCOMPLETE_EXPORT')
    normalized, rows, schemas, columns = [], {}, {}, {}
    table = None
    changes = 0
    for line in raw.splitlines(keepends=True):
        content = line[:-1] if line.endswith(b'\n') else line
        match = CREATE.fullmatch(content)
        if match:
            require(table is None, 'NESTED_TABLE')
            table = match[1].decode('ascii')
            require(table not in schemas, 'DUPLICATE_TABLE')
            schemas[table], rows[table], columns[table] = [], [], []
        normalized_line = line
        if table:
            column = re.match(rb'^  `([A-Za-z0-9_]+)` ', content)
            if column:
                columns[table].append(column[1].decode('ascii'))
            equivalent = COLUMN.fullmatch(content)
            if equivalent:
                normalized_line = equivalent[1] + equivalent[2] + b'\n'
                changes += normalized_line != line
            schemas[table].append(normalized_line)
            if content.startswith(b') ENGINE='):
                table = None
        insertion = INSERT.match(content)
        if insertion:
            name = insertion[1].decode('ascii')
            require(name in rows, 'INSERT_WITHOUT_TABLE')
            require(content.endswith(b');'), 'INCOMPLETE_INSERT')
            rows[name].append(line)
        normalized.append(normalized_line)
    require(table is None and schemas, 'INCOMPLETE_SCHEMA')
    return {'canonical': b''.join(normalized), 'rows': rows, 'schemas': schemas,
            'columns': columns, 'explicitCharsets': changes}


def compare(source, restored):
    a, b = inspect(source), inspect(restored)
    require(a['schemas'].keys() == b['schemas'].keys(), 'TABLE_SET_MISMATCH')
    for table in a['schemas']:
        require(a['schemas'][table] == b['schemas'][table], 'SCHEMA_MISMATCH:' + table)
        require(a['rows'][table] == b['rows'][table], 'ROW_CONTENT_MISMATCH:' + table)
    # Includes every SET statement, trigger, index, constraint, default and
    # migration INSERT. Nothing outside the narrow column rule is discarded.
    require(a['canonical'] == b['canonical'], 'EXPORT_CONTENT_MISMATCH')
    return {'sourceSha256': sha(source), 'restoredSha256': sha(restored),
            'canonicalSha256': sha(a['canonical']),
            'sourceExplicitCharsets': a['explicitCharsets'],
            'restoredExplicitCharsets': b['explicitCharsets'],
            'tables': {name: {'rows': len(a['rows'][name]),
                              'rowsSha256': sha(b''.join(a['rows'][name])),
                              'schemaSha256': sha(b''.join(a['schemas'][name])),
                              'columns': a['columns'][name]}
                       for name in a['schemas']}}
