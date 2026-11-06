// One scene per beat id. build(group) makes the objects once; update(refs, t, k, d) poses them for time t.
// t is seconds into the beat, k is t/d (0 to 1), d is the beat's length.
export default (kit) => {
  const { O, room, cam, drift, prog, ease, lerp, clay, rbox, motes } = kit;

  // The battery screen, used by the first two beats.
  const battery = (x, w, h, pct, color, big) => {
    x.fillStyle = '#07080a'; x.fillRect(0, 0, w, h);
    x.fillStyle = color; x.textAlign = 'center'; x.textBaseline = 'middle';
    x.font = `800 ${w * 0.3}px Inter`; x.fillText(`${Math.round(pct)}%`, w / 2, h * 0.42);
    const bw = w * 0.58, bh = h * 0.08, bx = (w - bw) / 2, by = h * 0.56;
    x.strokeStyle = '#f2f2f2'; x.lineWidth = w * 0.012; x.strokeRect(bx, by, bw, bh);
    x.fillStyle = '#f2f2f2'; x.fillRect(bx + bw + w * 0.012, by + bh * 0.28, w * 0.022, bh * 0.44);
    x.fillStyle = color; x.fillRect(bx + w * 0.018, by + w * 0.018, Math.max((bw - w * 0.036) * (pct / 100), w * 0.012), bh - w * 0.036);
    void big;
  };

  return {
    b1: {
      build(g) {
        room(g, 'ink', { keyPower: 42, angle: 0.34, keyX: -0.8, keyZ: 2.6 });
        const table = O.table(g, { color: '#2c2019' });
        const phone = O.phone(g, { power: 1.15 }); phone.rotation.x = -Math.PI / 2 + 0.02; phone.position.set(0, table.top + 0.04, 0.1);
        return { phone };
      },
      update({ phone }, t) {
        const on = Math.floor(t * 2.6) % 2 === 0;
        phone.screen.paint((x, w, h) => battery(x, w, h, 1, on ? '#ff3b30' : '#7a1a14'));
        // The phone buzzes in short bursts, so the very first frames already move.
        const buzz = Math.floor(t * 1.4) % 2 === 0 ? 1 - ((t * 1.4) % 1) : 0;
        phone.position.x = Math.sin(t * 95) * 0.022 * buzz;
        phone.rotation.z = Math.sin(t * 71) * 0.03 * buzz;
        const push = prog(t, 0, 2.4, ease.out), d = drift(t, 0.012);
        cam(d.x, lerp(5.4, 3.45, push) + d.y, lerp(2.2, 1.3, push), 0, 1.02, 0.1, 30);
      },
    },
    b2: {
      build(g) {
        room(g, 'ink', { keyPower: 48, angle: 0.42, keyX: -0.8, keyZ: 2.6 });
        const table = O.table(g, { color: '#2c2019' });
        const y = table.top + 0.03;
        const phone = O.phone(g, { power: 1.15 }); phone.rotation.x = -Math.PI / 2 + 0.02; phone.position.set(0, table.top + 0.04, 0.1);
        const cable = O.cable(g, [[-1.75, y, -0.75], [-1.2, y, -0.2], [-0.9, y, 0.45], [-0.45, y, 0.98], [0, y + 0.005, 1.1], [0, y + 0.005, 0.86]]);
        const plug = rbox(g, 0.11, 0.05, 0.2, 0.012, clay('#eeeae2'));
        return { phone, cable, plug };
      },
      update({ phone, cable, plug }, t, k, d) {
        // The cable lands on the word "charger", whatever the voice's timing turns out to be.
        const landed = kit.at('charger') + 0.2;
        const run = prog(t, 0, landed, ease.inOut);
        cable.show(run);
        const p = cable.curve.getPoint(run);
        plug.position.set(p.x, p.y + 0.012, p.z);
        const fill = prog(t, landed, d, ease.out);
        phone.screen.paint((x, w, h) => battery(x, w, h, 1 + fill * 99, run >= 1 ? '#39d36b' : '#ff3b30'));
        const dr = drift(t, 0.012);
        cam(dr.x + lerp(-0.2, 0, k), 3.7 + dr.y, 2 - k * 0.3, 0, 1.02, 0.2, 30);
      },
    },
    b3: {
      build(g) {
        room(g, 'night', { keyPower: 150, fillPower: 1.1, wallZ: -2.4 });
        const p = O.pendant(g, 3, { power: 14, core: 8, color: '#ffb066', shadow: true });
        return { p };
      },
      update({ p }, t) {
        const flick = 0.12 + 0.2 * Math.max(0, Math.sin(t * 19) * Math.sin(t * 5.3)) + (Math.floor(t * 7) % 5 === 0 ? 0.1 : 0);
        p.bulb.set(flick);
        const d = drift(t, 0.02);
        cam(d.x, 2.1 + d.y, lerp(5.4, 3.8, prog(t, 0, 2.6, ease.inOut)), 0, 2, 0, 30);
      },
    },
    b4: {
      build(g) {
        const r = room(g, 'night', { keyPower: 240, fillPower: 0.75, keyX: 1.6, keyZ: 4.5, angle: 0.62, wallZ: -2.6 });
        const table = O.table(g, { color: '#3e2a1d' });
        const mug = O.mug(g); mug.position.set(-1.05, table.top, 0.25);
        const books = [0, 1, 2, 3, 4, 5, 6].map((i) => { const b = O.book(g, { color: ['#3f5e6b', '#7c4a3a', '#55624a', '#7a6a3f'][i % 4] }); b.position.set(0.3, table.top + i * 0.145, 0.05); b.rotation.y = (i % 3) * 0.14 - 0.14; return b; });
        const clock = O.clock(g, { r: 0.5 }); clock.position.set(-0.1, 2.85, r.wall.position.z + 0.07);
        const dust = motes(g, { box: [4, 3.4, 2], n: 30 });
        return { books, clock, table, dust };
      },
      update({ books, clock, table, dust }, t, k) {
        dust.set(t);
        books.forEach((b, i) => {
          const a = prog(t, i * 0.34, i * 0.34 + 0.42, ease.drop);
          b.position.y = table.top + i * 0.145 + (1 - a) * 2.4;
          b.visible = t >= i * 0.34;
        });
        clock.set(22 + k * 6);
        const d = drift(t, 0.02);
        cam(d.x + lerp(0.4, -0.1, k), 1.9 + d.y, 9.6 - k * 0.5, 0, 1.45, 0, 28);
      },
    },
    b5: {
      build(g) {
        const r = room(g, 'clay', { keyPower: 90, fillPower: 0.1 });
        const p = O.pendant(g, 3.25, { power: 42, color: '#ffc27a', shadow: true });
        const wz = r.wall.position.z;
        const socket = O.socket(g); socket.position.set(0.75, 1.7, wz + 0.04);
        const cord = O.cable(g, [[0.75, 1.7, wz + 0.2], [1.1, 1.1, wz + 0.5], [1.5, 0.04, wz + 1.2], [2.2, 0.04, wz + 2.4]]);
        return { p, socket, r, cord, wz };
      },
      update({ p, socket, r, cord, wz }, t, k, d) {
        const seat = prog(t, 0.15, 0.6, ease.inOut);
        socket.seat(seat);
        cord.visible = seat > 0.9;
        const lit = prog(t, 0.62, Math.max(1.5, d * 0.45), ease.out);
        p.bulb.set(0.12 + lit * 0.88);
        r.key.intensity = 14 + lit * 90;
        r.fill.intensity = 0.14 + lit * 0.45;
        const back = prog(t, 0.65, 2.1, ease.inOut), dr = drift(t, 0.02);
        cam(lerp(0.75, 0, back) + dr.x, lerp(1.8, 2, back) + dr.y, lerp(wz + 2.9, 5.6 - k * 0.6, back), lerp(0.75, 0, back), lerp(1.68, 2.05, back), lerp(wz, 0, back), 31);
      },
    },
  };
};
