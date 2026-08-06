/** Shared Chinese labels & tooltips for benchmark metrics. */

export type MetricInfo = {
  key: string;
  name: string;
  abbr?: string;
  unit: string;
  desc: string;
};

export const METRICS = {
  ttft: {
    key: "ttft",
    name: "首 Token 时延",
    abbr: "TTFT",
    unit: "ms",
    desc: "从发出请求到收到第一个输出 Token 的等待时间；越小越“开得快”",
  },
  tpot: {
    key: "tpot",
    name: "单 Token 生成时延",
    abbr: "TPOT",
    unit: "ms",
    desc: "首 Token 之后，平均每个输出 Token 的生成耗时；反映续写速度",
  },
  itl: {
    key: "itl",
    name: "Token 间隔时延",
    abbr: "ITL",
    unit: "ms",
    desc: "相邻两个输出 Token 之间的间隔；波动大说明生成节奏不稳",
  },
  latency: {
    key: "latency",
    name: "端到端总耗时",
    abbr: undefined,
    unit: "ms",
    desc: "从请求发出到完整响应结束的总时间",
  },
  requestTps: {
    key: "request_tps",
    name: "请求吞吐",
    abbr: undefined,
    unit: "req/s",
    desc: "每秒完成的请求数",
  },
  outputTps: {
    key: "output_tps",
    name: "输出 Token 吞吐",
    abbr: undefined,
    unit: "tok/s",
    desc: "每秒生成的 Token 数",
  },
  totalTokenTps: {
    key: "total_token_tps",
    name: "总 Token 吞吐",
    abbr: undefined,
    unit: "tok/s",
    desc: "每秒处理的输入+输出 Token 数",
  },
  mean: {
    key: "mean",
    name: "平均值",
    unit: "",
    desc: "统计窗口内的算术平均",
  },
  p50: {
    key: "p50",
    name: "中位数",
    abbr: "P50",
    unit: "",
    desc: "一半请求优于该值；比均值更抗极端值",
  },
  p99: {
    key: "p99",
    name: "99 分位",
    abbr: "P99",
    unit: "",
    desc: "99% 请求不差于此；反映尾部最差体验",
  },
} as const;

export function metricTitle(m: MetricInfo): string {
  return m.abbr ? `${m.name} (${m.abbr})` : m.name;
}

export const METRIC_GLOSSARY: MetricInfo[] = [
  METRICS.ttft,
  METRICS.tpot,
  METRICS.itl,
  METRICS.latency,
  METRICS.requestTps,
  METRICS.outputTps,
  METRICS.totalTokenTps,
  METRICS.mean,
  METRICS.p50,
  METRICS.p99,
];
