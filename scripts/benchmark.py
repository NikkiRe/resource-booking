#!/usr/bin/env python3
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = 100
BOOKINGS_PER_RESOURCE = 10_000
LABEL = 'dev.nikita.booking.benchmark'


def command(args, *, data=None, timeout=300):
    result = subprocess.run(args, input=data, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr or result.stdout}')
    return result.stdout


def percentile(values, fraction):
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


def read_latencies(paths):
    values = []
    for path in paths:
        with path.open() as stream:
            for line in stream:
                fields = line.split()
                if len(fields) < 6 or not fields[2].isdigit():
                    raise ValueError(f'Invalid or failed transaction in {path.name}: {line.rstrip()}')
                values.append(int(fields[2]))
    if not values:
        raise ValueError('No transaction latency records')
    return sorted(values)


def pgbench_summary(output, paths):
    values = read_latencies(paths)
    processed = re.search(r'number of transactions actually processed:\s*(\d+)', output)
    failures = re.search(r'number of failed transactions:\s*(\d+)', output)
    tps = re.search(r'^tps = ([\d.]+)', output, re.MULTILINE)
    if not processed or not failures or not tps:
        raise ValueError('Incomplete pgbench output')
    if int(failures[1]) or int(processed[1]) != len(values):
        raise ValueError('pgbench failures or incomplete latency logs')
    return {
        'transactions': len(values),
        'failures': 0,
        'p50_ms': percentile(values, 0.50) / 1000,
        'p95_ms': percentile(values, 0.95) / 1000,
        'tps_excluding_initial_connections': float(tps[1]),
        'log_files': [str(path.name) for path in paths],
    }, values


def literal_query(template, resource_number, day_offset):
    return template.replace(':resource_number', str(resource_number)).replace(':day_offset', str(day_offset)).strip().rstrip(';')


def seed_sql():
    return f"""
DELETE FROM resources;
INSERT INTO resources (id, name, kind)
SELECT md5('benchmark-resource-' || n)::uuid, 'Benchmark resource ' || n, 'MEETING_ROOM'
FROM generate_series(1, {RESOURCES}) AS g(n);
INSERT INTO bookings (id, resource_id, booked_by, starts_at, ends_at, status, created_at, cancelled_at)
SELECT md5('benchmark-booking-' || r || '-' || n)::uuid,
       md5('benchmark-resource-' || r)::uuid, 'benchmark',
       starts, starts + INTERVAL '3 hours',
       CASE WHEN n % 10 = 0 THEN 'CANCELLED' ELSE 'ACTIVE' END,
       starts - INTERVAL '1 day',
       CASE WHEN n % 10 = 0 THEN starts - INTERVAL '12 hours' END
FROM generate_series(1, {RESOURCES}) AS resources(r)
CROSS JOIN generate_series(0, {BOOKINGS_PER_RESOURCE - 1}) AS slots(n)
CROSS JOIN LATERAL (
    SELECT TIMESTAMPTZ '2020-01-01 23:00:00+00' + n * INTERVAL '4 hours' AS starts
) times;
"""


def markdown(report):
    lines = ['# Resource usage SQL benchmark', '', f"Status: {report['status']}", '']
    if report.get('error'):
        lines += [report['error'], '']
    if 'dataset' in report:
        data = report['dataset']
        lines += [f"{data['bookings']:,} bookings, {data['resources']} resources, "
                  f"{data['active']:,} active and {data['cancelled']:,} cancelled.", '']
    lines += [f"PostgreSQL image: {report['config']['image']}. "
              f"{report['config']['clients']} connections, {report['config']['threads']} client workers. "
              f"Four measured runs of {report['config']['seconds']} s in raw/view/view/raw order, "
              f"each preceded by {report['config']['warmup_seconds']} s warmup.", '',
              'The query returns 31 UTC days for one resource, including zero days, resource name, '
              'counts, booked seconds and utilization. The separate freshness lookup and HTTP API are excluded. '
              'Both queries use the same database with all migration indexes enabled. '
              'The materialized view is refreshed before measurements; no writes or refresh run during load.', '']
    if 'summary' in report:
        for variant, metrics in report['summary'].items():
            lines += [f"{variant}: pooled p50 {metrics['p50_ms']:.3f} ms, "
                      f"pooled p95 {metrics['p95_ms']:.3f} ms, "
                      f"throughput {metrics['tps_excluding_initial_connections']:.1f} transactions/s."]
    if 'comparison' in report:
        lines += ['', f"Raw/view pooled p95 ratio: {report['comparison']['p95_raw_over_view']:.3f}x. "
                  f"View p95 change: {report['comparison']['p95_reduction_percent']:.2f}%.", '']
    if {'initial_seconds', 'unchanged_seconds'} <= report.get('refresh', {}).keys():
        lines += [f"Initial concurrent refresh: {report['refresh']['initial_seconds']:.3f} s. "
                  f"Unchanged concurrent refresh: {report['refresh']['unchanged_seconds']:.3f} s. "
                  'These wall-clock costs include Docker exec and psql startup.', '']
    for run in report.get('runs', []):
        lines += [f"Run {run['number']} ({run['variant']}): p50 {run['p50_ms']:.3f} ms, "
                  f"p95 {run['p95_ms']:.3f} ms, {run['tps_excluding_initial_connections']:.1f} transactions/s, "
                  f"{run['transactions']:,} completed transactions."]
    lines += ['', 'Percentiles use the nearest-rank method on every successful transaction from raw pgbench logs. '
              'Pooled percentiles weight each transaction equally across the two runs. Throughput is total '
              'transactions divided by the sum of measured durations inferred from each pgbench TPS, excluding '
              'initial connections. Queries, plans, raw logs, migrations, index definitions and machine context '
              'are included in the artifact. The client and database share the same constrained container; '
              'these are synthetic SQL measurements, not API latency or a production capacity claim.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description='Disposable PostgreSQL resource usage SQL benchmark')
    parser.add_argument('--output', type=Path, default=ROOT / 'benchmark-results')
    parser.add_argument('--image', default='postgres:17-alpine')
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--warmup-seconds', type=int, default=8)
    parser.add_argument('--clients', type=int, default=16)
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--container-name', default=f'booking-benchmark-{uuid.uuid4().hex[:12]}')
    parser.add_argument('--run-id', default=uuid.uuid4().hex)
    args = parser.parse_args()
    if min(args.seconds, args.warmup_seconds, args.clients, args.threads) < 1:
        parser.error('Durations, clients and threads must be positive')
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]+', args.container_name):
        parser.error('Invalid container name')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error('Output directory must be empty')
    (output / 'logs').mkdir()
    report = {
        'status': 'running', 'started_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'config': {key: value for key, value in vars(args).items() if key != 'output'},
        'method': {
            'scope': '31-day daily usage SQL for one resource, freshness query and HTTP excluded',
            'percentile': 'nearest-rank over all per-transaction microsecond logs',
            'order': ['raw', 'view', 'view', 'raw'],
            'query_mode': 'prepared', 'seed_policy': 'same random seed within each raw/view pair',
            'refresh_during_load': False, 'writes_during_load': False,
            'container_cpus': 2, 'container_memory_bytes': 3 * 1024**3,
        }, 'runs': [],
    }
    created = False
    pending_error = None

    def psql(sql, *, timeout=900):
        return command(['docker', 'exec', '-i', args.container_name, 'psql', '-X', '-qAt',
                        '-v', 'ON_ERROR_STOP=1', '-U', 'benchmark', '-d', 'booking'], data=sql, timeout=timeout)

    def save_report():
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        (output / 'report.md').write_text(markdown(report))

    def interrupted(signum, _frame):
        raise InterruptedError(f'Interrupted by signal {signum}')

    signal.signal(signal.SIGTERM, interrupted)
    try:
        save_report()
        command(['docker', 'pull', args.image], timeout=600)
        command(['docker', 'create', '--name', args.container_name,
                 '--label', f'{LABEL}={args.run_id}', '--cpus=2', '--memory=3g', '--shm-size=256m',
                 '-e', 'POSTGRES_USER=benchmark', '-e', 'POSTGRES_PASSWORD=benchmark',
                 '-e', 'POSTGRES_DB=booking', '-e', 'TZ=UTC',
                 '--mount', f'type=bind,source={output},target=/bench',
                 args.image, 'postgres', '-c', 'timezone=UTC'])
        created = True
        command(['docker', 'start', args.container_name])
        deadline = time.monotonic() + 90
        while True:
            try:
                command(['docker', 'exec', '-e', 'PGPASSWORD=benchmark', args.container_name,
                         'psql', '-h', '127.0.0.1', '-U', 'benchmark', '-d', 'booking',
                         '-X', '-qAt', '-c', 'SELECT 1;'], timeout=5)
                break
            except (RuntimeError, subprocess.TimeoutExpired):
                if time.monotonic() >= deadline:
                    raise RuntimeError('PostgreSQL was not ready within 90 seconds')
                time.sleep(0.5)
        if int(psql("SELECT current_setting('server_version_num');")) // 10_000 != 17:
            raise RuntimeError('This benchmark requires PostgreSQL 17')
        report['context'] = {
            'host_platform': platform.platform(), 'host_logical_cpus': os.cpu_count(),
            'github': {key: os.environ[key] for key in
                       ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'RUNNER_OS', 'RUNNER_ARCH')
                       if key in os.environ},
            'image': json.loads(command(['docker', 'image', 'inspect', args.image,
                                         '--format', '{{json .RepoDigests}}'])),
            'image_id': command(['docker', 'inspect', args.container_name, '--format', '{{.Image}}']).strip(),
            'container': command(['docker', 'exec', args.container_name, 'sh', '-c',
                                  'uname -a; cat /proc/cpuinfo; cat /proc/meminfo']).strip(),
            'postgresql': psql("SELECT version(); SELECT name || '=' || setting FROM pg_settings "
                               "WHERE name IN ('shared_buffers','work_mem','effective_cache_size','jit',"
                               "'max_parallel_workers_per_gather','max_connections','TimeZone') ORDER BY name;").strip(),
            'pgbench': command(['docker', 'exec', args.container_name, 'pgbench', '--version']).strip(),
            'client_and_db_share_container': True,
        }
        migration_dir = ROOT / 'src/main/resources/db/migration'
        migrations = sorted(migration_dir.glob('V*__*.sql'), key=lambda path: int(path.name.split('__')[0][1:]))
        if not migrations:
            raise RuntimeError('No database migrations found')
        report['migrations'] = []
        (output / 'migrations').mkdir()
        for migration in migrations:
            sql = migration.read_text()
            psql('BEGIN;\n' + sql + '\nCOMMIT;')
            (output / 'migrations' / migration.name).write_text(sql)
            report['migrations'].append({'file': migration.name, 'sha256': hashlib.sha256(sql.encode()).hexdigest()})
        started = time.perf_counter()
        psql(seed_sql())
        report['seed_seconds'] = time.perf_counter() - started
        data = json.loads(psql("""
SELECT json_build_object(
    'resources', (SELECT count(*) FROM resources), 'bookings', count(*),
    'active', count(*) FILTER (WHERE status='ACTIVE'),
    'cancelled', count(*) FILTER (WHERE status='CANCELLED'),
    'crosses_utc_midnight', count(*) FILTER (WHERE starts_at::date <> ends_at::date),
    'active_crosses_utc_midnight', count(*) FILTER (WHERE status='ACTIVE' AND starts_at::date <> ends_at::date),
    'from_utc', min(starts_at), 'to_utc', max(ends_at),
    'max_day_offset', max(ends_at)::date - DATE '2020-01-01' - 30,
    'active_booked_seconds', sum(extract(epoch FROM ends_at-starts_at)) FILTER (WHERE status='ACTIVE')
) FROM bookings;
"""))
        if data['bookings'] != RESOURCES * BOOKINGS_PER_RESOURCE or data['resources'] != RESOURCES:
            raise ValueError('Unexpected dataset cardinality')
        data['generator'] = '100 resources x 10000 three-hour intervals, four-hour stride from 2020-01-01 23:00 UTC; every tenth interval cancelled'
        data['demo_resources'] = 'V2 applied, its two resources deleted before deterministic seed'
        report['dataset'] = data
        (output / 'seed.sql').write_text(seed_sql())
        report['refresh'] = {}
        for label in ('initial', 'unchanged'):
            started = time.perf_counter()
            psql('REFRESH MATERIALIZED VIEW CONCURRENTLY resource_daily_usage;')
            report['refresh'][label + '_seconds'] = time.perf_counter() - started
            if label == 'initial':
                psql('VACUUM (ANALYZE) bookings; VACUUM (ANALYZE) resources; VACUUM (ANALYZE) resource_daily_usage;')
        totals = json.loads(psql('SELECT json_build_object(\'rows\', count(*), \'booking_count\', sum(booking_count), '
                                "'booked_seconds', sum(booked_seconds)) FROM resource_daily_usage;"))
        if (totals['booking_count'] != data['active'] + data['active_crosses_utc_midnight']
                or totals['booked_seconds'] != data['active_booked_seconds']):
            raise ValueError('Materialized view totals differ from independently computed booking totals')
        report['dataset']['view_totals'] = totals
        (output / 'indexes.txt').write_text(psql('SELECT schemaname, tablename, indexname, indexdef FROM pg_indexes WHERE schemaname=\'public\' ORDER BY tablename,indexname;'))
        templates = {variant: (ROOT / f'scripts/benchmark_{variant}.sql').read_text() for variant in ('raw', 'view')}
        report['queries'] = {}
        for variant, template in templates.items():
            (output / f'{variant}.sql').write_text(
                f'\\set resource_number random(1, {RESOURCES})\n'
                f"\\set day_offset random(0, {data['max_day_offset']})\n" + template)
            report['queries'][variant] = hashlib.sha256(template.encode()).hexdigest()
        checks = []
        for resource in (1, 17, 50, 100):
            for offset in (-31, 0, 17, 365, 1000, data['max_day_offset'], data['max_day_offset'] + 30, data['max_day_offset'] + 62):
                raw = literal_query(templates['raw'], resource, offset)
                view = literal_query(templates['view'], resource, offset)
                count = int(psql(f'WITH raw_result AS ({raw}), view_result AS ({view}) '
                                 'SELECT count(*) FROM ((SELECT * FROM raw_result EXCEPT ALL SELECT * FROM view_result) '
                                 'UNION ALL (SELECT * FROM view_result EXCEPT ALL SELECT * FROM raw_result)) difference;'))
                if count:
                    raise ValueError(f'Query mismatch: resource={resource}, offset={offset}')
                checks.append({'resource_number': resource, 'day_offset': offset, 'differences': count})
        report['equivalence_checks'] = checks
        for variant, template in templates.items():
            plan = psql('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' + literal_query(template, 50, 365) + ';')
            (output / f'plan-{variant}.json').write_text(plan)
        save_report()
        all_values = {'raw': [], 'view': []}
        for number, variant in enumerate(('raw', 'view', 'view', 'raw'), 1):
            seed = 70681 + (number - 1) // 2
            common = ['docker', 'exec', args.container_name, 'pgbench', '-U', 'benchmark', '-n',
                      '-c', str(args.clients), '-j', str(args.threads), '-M', 'prepared',
                      '--exit-on-abort', f'--random-seed={seed}', '-f', f'/bench/{variant}.sql']
            print(f'Run {number}/4: {variant}, warmup {args.warmup_seconds}s, measurement {args.seconds}s', flush=True)
            warmup = command(common + ['-T', str(args.warmup_seconds), 'booking'], timeout=args.warmup_seconds + 60)
            (output / f'warmup-{number}-{variant}.txt').write_text(warmup)
            if not re.search(r'number of failed transactions:\s*0\b', warmup):
                raise ValueError('Warmup contains failed transactions')
            prefix = f'{number}-{variant}'
            measured = command(common + ['-T', str(args.seconds), '-l',
                                         f'--log-prefix=/bench/logs/{prefix}', 'booking'], timeout=args.seconds + 60)
            (output / f'run-{prefix}.txt').write_text(measured)
            paths = sorted((output / 'logs').glob(prefix + '.*'))
            metrics, values = pgbench_summary(measured, paths)
            metrics.update(number=number, variant=variant, random_seed=seed, seconds=args.seconds)
            report['runs'].append(metrics)
            all_values[variant].extend(values)
            save_report()
        report['summary'] = {}
        for variant, values in all_values.items():
            values.sort()
            runs = [run for run in report['runs'] if run['variant'] == variant]
            measured_seconds = sum(run['transactions'] / run['tps_excluding_initial_connections'] for run in runs)
            report['summary'][variant] = {
                'transactions': len(values), 'p50_ms': percentile(values, 0.50) / 1000,
                'p95_ms': percentile(values, 0.95) / 1000,
                'tps_excluding_initial_connections': len(values) / measured_seconds,
            }
        raw_p95 = report['summary']['raw']['p95_ms']
        view_p95 = report['summary']['view']['p95_ms']
        report['comparison'] = {
            'p95_raw_over_view': raw_p95 / view_p95,
            'p95_reduction_percent': (raw_p95 - view_p95) / raw_p95 * 100,
        }
        report['status'] = 'passed'
    except BaseException as error:
        report['status'] = 'failed'
        report['error'] = f'{type(error).__name__}: {error}'
        pending_error = error
    finally:
        report['finished_at_utc'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        if created:
            try:
                logs = subprocess.run(['docker', 'logs', args.container_name],
                                      text=True, capture_output=True, timeout=15)
                (output / 'postgres.log').write_text(logs.stdout + logs.stderr)
            except BaseException as log_error:
                report['postgres_log_error'] = str(log_error)
            try:
                owner = command(['docker', 'inspect', args.container_name, '--format',
                                 f'{{{{index .Config.Labels "{LABEL}"}}}}']).strip()
                if owner != args.run_id:
                    raise RuntimeError('Refusing to remove container with a different ownership label')
                command(['docker', 'rm', '-f', '-v', args.container_name])
                report['container_removed'] = True
            except BaseException as cleanup_error:
                report['cleanup_error'] = str(cleanup_error)
                report['status'] = 'failed'
                pending_error = pending_error or cleanup_error
        save_report()
    print(f"Benchmark {report['status']}: {output / 'report.json'}", flush=True)
    if pending_error:
        raise pending_error


if __name__ == '__main__':
    main()
