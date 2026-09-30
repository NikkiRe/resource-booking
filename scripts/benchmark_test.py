import tempfile
from pathlib import Path
import unittest

from benchmark import markdown, pgbench_summary, read_latencies


class LogMetricsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def log(self, name, values):
        path = self.root / name
        path.write_text(''.join(f'0 {number} {value} 0 1700000000 1\n'
                                for number, value in enumerate(values)))
        return path

    def output(self, transactions=100, failures=0):
        return (f'number of transactions actually processed: {transactions}\n'
                f'number of failed transactions: {failures} (0.000%)\n'
                'latency average = 999.0 ms\n'
                'tps = 50.000000 (without initial connection time)\n')

    def test_percentiles_use_all_worker_logs_and_microseconds(self):
        first = self.log('run.100', range(1000, 100001, 2000))
        second = self.log('run.100.1', range(2000, 100001, 2000))
        metrics, _ = pgbench_summary(self.output(), [first, second])
        self.assertEqual(50, metrics['p50_ms'])
        self.assertEqual(95, metrics['p95_ms'])
        self.assertEqual(100, metrics['transactions'])
        self.assertEqual(50, metrics['tps_excluding_initial_connections'])

    def test_lost_logs_reject_measurement(self):
        path = self.log('run.100', [1000])
        with self.assertRaisesRegex(ValueError, 'incomplete latency logs'):
            pgbench_summary(self.output(), [path])

    def test_reported_failures_reject_measurement(self):
        path = self.log('run.100', [1000])
        with self.assertRaisesRegex(ValueError, 'pgbench failures'):
            pgbench_summary(self.output(transactions=1, failures=1), [path])

    def test_failed_transaction_log_rejects_measurement(self):
        path = self.log('run.100', ['failed'])
        with self.assertRaisesRegex(ValueError, 'failed transaction'):
            read_latencies([path])

    def test_empty_logs_reject_measurement(self):
        with self.assertRaisesRegex(ValueError, 'No transaction'):
            read_latencies([])

    def test_error_report_can_be_written_mid_refresh(self):
        report = {'status': 'failed', 'error': 'refresh interrupted',
                  'config': {'image': 'postgres:17-alpine', 'clients': 16, 'threads': 4,
                             'seconds': 30, 'warmup_seconds': 8},
                  'refresh': {'initial_seconds': 5.0}, 'runs': []}
        self.assertIn('refresh interrupted', markdown(report))

    def test_error_report_can_be_written_mid_summary(self):
        report = {'status': 'failed', 'error': 'summary interrupted',
                  'config': {'image': 'postgres:17-alpine', 'clients': 16, 'threads': 4,
                             'seconds': 30, 'warmup_seconds': 8},
                  'summary': {'raw': {'p50_ms': 1, 'p95_ms': 2,
                                      'tps_excluding_initial_connections': 3}}, 'runs': []}
        self.assertIn('summary interrupted', markdown(report))


if __name__ == '__main__':
    unittest.main()
