import http from "node:http";

const itemId = process.argv[2];
const role = process.argv[3];
const supported = Array.from({length: 11}, (_, index) => `@item-01-${String(index + 1).padStart(3, "0")}`);
if (!supported.includes(itemId)) throw new Error(`unsupported calibration item: ${itemId}`);
if (!['carried', 'uncarried'].includes(role)) throw new Error(`unsupported calibration role: ${role}`);

const rooms = new Map();
const slots = new Map();
let nextId = 1;
const keyOf = (body) => `${body['dressing-room']}|${body.weekday}|${body.start}`;
const endTime = (start) => {
  const [hour, minute] = start.split(':').map(Number);
  const total = hour * 60 + minute + 90;
  return `${String(Math.floor(total / 60) % 24).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
};
const readBody = async (request) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString() || '{}');
};
const send = (response, status, value) => {
  response.statusCode = status;
  response.setHeader('content-type', 'application/json');
  response.end(JSON.stringify(value));
};
const schedule = (sauna) => ({
  sauna,
  'dressing-rooms': [...rooms.entries()].filter(([, room]) => room.sauna === sauna).map(([name, room]) => ({
    'dressing-room': name,
    sauna,
    slots: room.weekdays.flatMap((weekday) => room.slots.map((start) => {
      const held = slots.get(`${name}|${weekday}|${start}`);
      return {weekday, start, end: endTime(start), status: held ? 'held' : 'free',
              ...(held ? {household: held.household} : {})};
    })),
  })),
});

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://fixture');
  if (request.method === 'PUT' && url.pathname.startsWith('/api/rooms/') && url.pathname.endsWith('/days')) {
    const name = decodeURIComponent(url.pathname.slice('/api/rooms/'.length, -'/days'.length));
    const body = await readBody(request);
    rooms.set(name, {sauna: String(body.sauna), weekdays: body.weekdays || [], slots: body.slots || []});
    return send(response, 200, {message: `Saved: ${name} opens on ${(body.weekdays || []).join(', ')} at ${(body.slots || []).join(', ')}.`,
                                'dressing-room': name, ...body});
  }
  if (request.method === 'POST' && url.pathname === '/api/standing-slots') {
    const body = await readBody(request);
    const room = rooms.get(body['dressing-room']);
    const closed = !room || !room.weekdays.includes(body.weekday) || !room.slots.includes(body.start);
    if (closed) {
      if (role === 'uncarried' && itemId === '@item-01-002') {
        return send(response, 201, {status: 'confirmed', ...body, end: endTime(body.start)});
      }
      const actionable = !(role === 'uncarried' && itemId === '@item-01-011');
      return send(response, 409, actionable
        ? {error: 'ClosedDay', message: `${body['dressing-room']} is not open on ${body.weekday}.`,
           'available-days': room?.weekdays || [], 'available-slots': room?.slots || []}
        : {error: 'ClosedDay', message: 'No.'});
    }
    if (role === 'uncarried' && itemId === '@item-01-003') {
      return send(response, 409, {error: 'Refused'});
    }
    const key = keyOf(body);
    const existing = slots.get(key);
    const admitConflict = role === 'uncarried' &&
      ['@item-01-004', '@item-01-006', '@item-01-009', '@item-01-010'].includes(itemId);
    if (existing && !admitConflict) {
      return send(response, 409, {error: 'Held', message: `${body.weekday} at ${body.start} in ${body['dressing-room']} is held by another household.`});
    }
    if (role === 'uncarried' && itemId === '@item-01-005' &&
        [...slots.values()].some((slot) => slot.household === body.household)) {
      return send(response, 409, {error: 'NoRoom', message: 'No room.'});
    }
    const slot = {...body, 'slot-id': String(nextId++), end: endTime(body.start)};
    slots.set(key, slot);
    return send(response, 201, {status: 'confirmed', ...slot,
      message: `Confirmed: apartment ${body.household} holds ${body.weekday} ${body.start} to ${slot.end} in ${body['dressing-room']}, every week until it gives the slot up.`});
  }
  if (request.method === 'POST' && url.pathname === '/api/standing-slots/give-up') {
    const body = await readBody(request);
    const key = keyOf(body);
    const existing = slots.get(key);
    if (!existing || existing.household !== body.household) return send(response, 404, {error: 'NotHeld'});
    if (!(role === 'uncarried' && itemId === '@item-01-008')) slots.delete(key);
    return send(response, 200, {message: `Apartment ${body.household} gave up ${body.weekday} ${body.start} in ${body['dressing-room']}.`});
  }
  if (request.method === 'GET' && url.pathname === '/api/schedule') {
    const value = schedule(url.searchParams.get('sauna'));
    if (role === 'uncarried' && itemId === '@item-01-001') {
      const room = value['dressing-rooms'].find((entry) => entry['dressing-room'] === 'pukuhuone 9');
      if (room) room.slots.push({weekday: 'tuesday', start: '17:00', end: '18:30', status: 'free'});
    }
    return send(response, 200, value);
  }
  if (request.method === 'GET' && url.pathname === '/api/heater-schedule') {
    const sauna = url.searchParams.get('sauna');
    return send(response, 200, {sauna, periods: [...slots.values()]
      .filter((slot) => rooms.get(slot['dressing-room'])?.sauna === sauna)
      .map((slot) => ({'standing-slot-id': slot['slot-id'], 'dressing-room': slot['dressing-room'],
                       weekday: slot.weekday, start: slot.start, end: slot.end}))});
  }
  if (request.method === 'GET' && url.pathname === '/api/billing-signals') {
    const household = url.searchParams.get('household');
    const signals = [...slots.values()].filter((slot) => slot.household === household)
      .map((slot) => ({'standing-slot-id': slot['slot-id'], household: slot.household,
                       weekday: slot.weekday, start: slot.start}));
    return send(response, 200, {household,
      signals: role === 'uncarried' && itemId === '@item-01-007' ? [] : signals});
  }
  send(response, 404, {error: 'not found'});
});

server.listen(0, '127.0.0.1', () => {
  const address = server.address();
  console.log(`http://127.0.0.1:${address.port}`);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
