const EMAIL = /^[a-z0-9._%+-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/i;
const TOPIC_WORD = /^[A-Za-z0-9]{1,40}$/;
const HANDLE = /^[A-Za-z0-9_-]{43}$/;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;
const MAX_DATE_RANGE_DAYS = 366;

export function validateGmailSearchFilters({ keyword = '', sender = '', startDate = '', endDate = '' }) {
  const topic = keyword.trim();
  const exactSender = sender.trim();
  const from = startDate.trim();
  const to = endDate.trim();

  if (topic) {
    const terms = topic.split(/\s+/);
    if (terms.length > 3 || terms.some(term => !TOPIC_WORD.test(term))) {
      return { error: 'Use up to three alphanumeric keyword terms (letters and numbers only).' };
    }
  }
  if (exactSender && (exactSender.length > 254 || !EMAIL.test(exactSender))) {
    return { error: 'Enter one exact sender email address.' };
  }
  if (Boolean(from) !== Boolean(to)) {
    return { error: 'Enter both dates to use a date range.' };
  }
  if (from) {
    const validDate = value => /^\d{4}-\d{2}-\d{2}$/.test(value) &&
      !Number.isNaN(Date.parse(`${value}T00:00:00Z`)) &&
      new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
    if (!validDate(from) || !validDate(to) || to === '9999-12-31') {
      return { error: 'Enter valid calendar dates supported by Gmail search.' };
    }
    const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000;
    if (days < 0) return { error: 'The To date must be on or after the From date.' };
    if (days >= MAX_DATE_RANGE_DAYS) return { error: 'Choose a date range of no more than 366 inclusive days.' };
  }
  if (!topic && !exactSender && !from) {
    return { error: 'Enter a keyword, sender or complete date range to search Gmail.' };
  }

  return { filters: {
    ...(topic ? { keyword: topic } : {}),
    ...(exactSender ? { sender: exactSender } : {}),
    ...(from ? { start_date: from, end_date: to } : {}),
  } };
}

export function validGmailSearchResponse(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).sort().join(',') !== 'coverage_warning,partial_results,results' ||
      !Array.isArray(value.results) || value.results.length > 20 || typeof value.partial_results !== 'boolean') return false;
  if (value.partial_results ? typeof value.coverage_warning !== 'string' || !value.coverage_warning.trim() || value.coverage_warning.length > 300
      : value.coverage_warning !== null) return false;
  const selections = new Set();
  return value.results.every(row => {
    if (!row || typeof row !== 'object' || Array.isArray(row) ||
        Object.keys(row).sort().join(',') !== 'excerpt,received_at,selection_id,sender,subject' ||
        !(row.subject == null || typeof row.subject === 'string' && row.subject.length <= 512) ||
        !(row.sender == null || typeof row.sender === 'string' && row.sender.length <= 512) ||
        typeof row.excerpt !== 'string' || row.excerpt.length > 600 ||
        typeof row.selection_id !== 'string' || !HANDLE.test(row.selection_id) || selections.has(row.selection_id)) return false;
    selections.add(row.selection_id);
    return row.received_at == null || typeof row.received_at === 'string' && INSTANT.test(row.received_at) &&
      !Number.isNaN(Date.parse(row.received_at));
  });
}
