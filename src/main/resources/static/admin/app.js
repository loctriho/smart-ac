/* global $, Chart */
(function () {
  'use strict';

  const API = '/api/admin';
  const DEVICE_CHART_RANGE_KEY = 'smartac.deviceChartRange';
  const RANGE_OPTIONS = [
    { key: 'all', label: 'All time' },
    { key: 'today', label: 'Today' },
    { key: 'week', label: 'This week' },
    { key: 'month', label: 'This month' },
    { key: 'year', label: 'This year' },
  ];
  const DEVICE_RANGE_KEYS = new Set(RANGE_OPTIONS.map(function (o) {
    return o.key;
  }));

  let es = null;
  let reconnectTimer = null;
  let deviceViewState = {
    id: null,
    range: 'today',
  };

  let devicesListState = {
    q: '',
    page: 0,
    size: 100,
    total: 0,
  };

  let openNotificationsState = {
    page: 0,
    size: 100,
    hasMore: false,
  };

  let dashboardListState = {
    size: 100,
    total: 0,
    afterId: null,
    prevStack: [],
    lastSeenId: null,
  };

  // Cache the last dashboard snapshot so navigating away/back does not re-fetch.
  let dashboardCache = {
    html: '',
    updatedText: '—',
    deviceCount: 0,
  };

  /** Latest series rows for the open device detail page (used by Enlarge modal). */
  const deviceChartSeriesCache = {
    temperature: [],
    humidity: [],
    co: [],
  };

  /** Inline Chart.js instances on the device detail page (destroyed on navigation / reload). */
  const deviceInlineCharts = {
    temperature: null,
    humidity: null,
    co: null,
  };

  let modalChartInstance = null;

  function destroyDeviceInlineCharts() {
    ['temperature', 'humidity', 'co'].forEach(function (k) {
      if (deviceInlineCharts[k]) {
        deviceInlineCharts[k].destroy();
        deviceInlineCharts[k] = null;
      }
    });
  }

  function destroyModalChart() {
    if (modalChartInstance) {
      modalChartInstance.destroy();
      modalChartInstance = null;
    }
  }

  function parseRoute() {
    const raw = (location.hash || '#dashboard').replace(/^#/, '').trim();
    if (!raw || raw === 'dashboard') return { name: 'dashboard' };
    if (raw === 'devices') return { name: 'devices' };
    if (raw === 'notifications') return { name: 'notifications' };
    if (raw === 'admins') return { name: 'admins' };
    if (raw === 'invitations') return { name: 'invitations' };
    const m = /^devices\/(\d+)$/.exec(raw);
    if (m) return { name: 'device', id: m[1] };
    return { name: 'dashboard' };
  }

  function setNavActive(route) {
    $('.nav a[data-nav]').removeClass('active');
    const key =
      route.name === 'device' ? 'devices' : route.name === 'invitations' ? 'invitations' : route.name;
    $('.nav a[data-nav="' + key + '"]').addClass('active');
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function fmtNum(v) {
    if (v == null || v === '') return '—';
    const x = Number(v);
    return Number.isFinite(x) ? x.toFixed(1) : String(v);
  }

  function fmtTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return Number.isFinite(d.getTime()) ? d.toLocaleString() : String(iso);
  }

  function validDeviceRange(r) {
    return DEVICE_RANGE_KEYS.has(r) ? r : 'today';
  }

  function setNotificationNavBadge(n) {
    const c = Number(n) || 0;
    const $b = $('#nav-badge');
    if (c > 0) {
      $b.text(c).removeAttr('hidden');
    } else {
      $b.attr('hidden', 'hidden').text('0');
    }
  }

  function fetchOpenCount() {
    return $.getJSON(API + '/notifications/open-count')
      .done(function (d) {
        setNotificationNavBadge(d.count);
      })
      .fail(function () {
        /* leave badge unchanged */
      });
  }

  function setLive(ok, msg) {
    $('#live-status').removeClass('ok err').addClass(ok ? 'ok' : 'err').text('Live: ' + msg);
  }

  function connectSse() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (es) {
      es.close();
      es = null;
    }
    try {
      es = new EventSource(API + '/stream');
    } catch (e) {
      setLive(false, 'SSE unavailable');
      scheduleReconnect();
      return;
    }
    es.onopen = function () {
      setLive(true, 'connected (server push)');
      fetchOpenCount();
    };
    es.onmessage = function (ev) {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch (e) {
        return;
      }
      if (!msg || !msg.topic) return;
      const route = parseRoute();
      if (msg.topic === 'hello' || msg.topic === 'ping') return;
      if (msg.topic === 'readings') {
        fetchOpenCount();
        if (route.name === 'device' && String(msg.deviceId) === String(route.id)) {
          loadDeviceDetail(route.id, { preserveRange: true });
        }
      }
      if (msg.topic === 'notifications') {
        fetchOpenCount();
        if (route.name === 'notifications') loadOpenNotificationsPage();
        if (route.name !== 'notifications') {
          showNotificationToast('New admin notification — open Notifications to review or resolve.');
        }
      }
      if (msg.topic === 'devices') {
        fetchOpenCount();
        // Do not refresh dashboard from backend; just bump the displayed device count.
        if (route.name === 'dashboard') {
          const el = document.getElementById('live-device-count');
          if (el) {
            const cur = Number(String(el.textContent || '').replace(/[^\d]/g, ''));
            if (Number.isFinite(cur) && cur >= 0) {
              el.textContent = String(cur + 1);
            }
          }
        }
        if (route.name === 'devices' && $('#devices-tbody').length) {
          // Device list refreshes do not need COUNT(*) every time.
          loadDevicesSummary({ includeTotal: false });
        }
      }
    };
    es.onerror = function () {
      setLive(false, 'reconnecting…');
      if (es) {
        es.close();
        es = null;
      }
      scheduleReconnect();
    };
  }

  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(function () {
      reconnectTimer = null;
      connectSse();
    }, 3000);
  }

  let toastTimer = null;
  function showNotificationToast(text) {
    const $t = $('#app-toast');
    if (!$t.length) return;
    $t.text(text).removeAttr('hidden');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      $t.attr('hidden', 'hidden').text('');
    }, 8000);
  }

  function buildDashboardBodyHtml(d) {
    let rows = '';
    (d.deviceSnapshots || []).forEach(function (s) {
      const lr = s.lastReading;
      rows +=
        '<tr><td>' +
        escapeHtml(s.serialNumber) +
        '</td><td>' +
        (lr ? fmtTime(lr.recordedAt) : '<span class="muted">—</span>') +
        '</td><td>' +
        (lr ? fmtNum(lr.temperatureCelsius) : '<span class="muted">—</span>') +
        '</td><td>' +
        (lr ? fmtNum(lr.humidityPercent) : '<span class="muted">—</span>') +
        '</td><td>' +
        (lr ? fmtNum(lr.carbonMonoxidePpm) : '<span class="muted">—</span>') +
        '</td><td>' +
        (lr ? escapeHtml(lr.healthStatus || '—') : '<span class="muted">—</span>') +
        '</td><td><a class="btn" href="#devices/' +
        s.deviceId +
        '">Details</a></td></tr>';
    });
    if (!rows) {
      rows = '<tr><td colspan="7" class="muted">No devices yet.</td></tr>';
    }
    return (
      '<section class="stats"><div><strong id="live-device-count">' +
      d.deviceCount +
      '</strong> devices</div></section>' +
      '<div class="table-wrap"><table><thead><tr><th>Serial</th><th>Last sample</th><th>Temp °C</th><th>Humidity %</th><th>CO PPM</th><th>Health</th><th></th></tr></thead><tbody>' +
      rows +
      '</tbody></table></div>'
    );
  }

  function applyDashboardState(d) {
    $('#dash-sync').removeClass('syncing').text('Idle');
    $('#dash-err').attr('hidden', 'hidden');
    $('#dash-updated').text(new Date().toLocaleTimeString());
    setNotificationNavBadge(d.openNotificationCount);
    dashboardListState.total = Number(d.deviceCount) || 0;
    const snaps = d.deviceSnapshots || [];
    dashboardListState.lastSeenId = snaps.length ? snaps[snaps.length - 1].deviceId : null;
    const body = buildDashboardBodyHtml(d);
    $('#dash-body').html(body);
    renderDashboardPager();

    dashboardCache.html = body;
    dashboardCache.updatedText = $('#dash-updated').text() || '—';
    dashboardCache.deviceCount = Number(d.deviceCount) || 0;
  }

  function refreshDashboardData() {
    if (parseRoute().name !== 'dashboard') return;
    if (!$('#dash-body').length) return;
    $('#dash-sync').addClass('syncing').text('Syncing…');
    const params = {
      size: String(dashboardListState.size || 100),
    };
    if (dashboardListState.afterId != null) {
      params.afterId = String(dashboardListState.afterId);
    }
    $.getJSON(API + '/dashboard-state', params)
      .done(applyDashboardState)
      .fail(function () {
        $('#dash-sync').removeClass('syncing').text('Idle');
        $('#dash-err').removeAttr('hidden');
      });
  }

  function renderDashboardPager() {
    const total = Number(dashboardListState.total) || 0;
    const size = Number(dashboardListState.size) || 100;
    const pageNum = (dashboardListState.prevStack || []).length + 1;
    const atStart = (dashboardListState.prevStack || []).length === 0;
    // We can't know "atEnd" without an extra lookahead query; disable Next only when a page returns < size.
    const atEnd = dashboardListState.lastSeenId == null;
    $('#dash-pager').html(
      '<div class="pager-left">' +
        '<span class="pager-meta">Page <strong>' +
        pageNum +
        '</strong> · Showing <strong>' +
        size +
        '</strong> per page · Total <strong>' +
        total +
        '</strong></span>' +
        '</div>' +
        '<div class="pager-right">' +
        '<button type="button" class="btn small ghost" id="dash-prev" ' +
        (atStart ? 'disabled' : '') +
        '>Prev</button>' +
        '<button type="button" class="btn small ghost" id="dash-next" ' +
        (atEnd ? 'disabled' : '') +
        '>Next</button>' +
        '</div>',
    );
  }

  function loadDashboard() {
    $('#app-main').html(
      '<div class="wrap">' +
        '<h1>Dashboard</h1>' +
        '<p class="meta">' +
        '<span id="dash-sync" class="syncing">Syncing…</span> · Last OK: <span id="dash-updated">—</span> ' +
        '<span id="dash-err" class="err" hidden>· Request failed</span> · ' +
        '<span class="muted">Reload the browser to refresh</span>' +
        '</p>' +
        '<p class="muted dash-hint">Dashboard data does not auto-refresh; reload the browser if you want a fresh snapshot. ' +
        'If you use <code>mvn test</code> without the <code>mysql-test</code> profile, devices are written to in-memory H2, not the MariaDB database this server uses.</p>' +
        '<div class="pager" id="dash-pager"></div>' +
        '<div id="dash-body"></div></div>',
    );

    // Avoid duplicate handlers when navigating back to Dashboard (or re-rendering).
    $('#app-main').off('click', '#dash-prev');
    $('#app-main').off('click', '#dash-next');

    $('#app-main').on('click', '#dash-prev', function () {
      if (!dashboardListState.prevStack || !dashboardListState.prevStack.length) return;
      dashboardListState.afterId = dashboardListState.prevStack.pop();
      refreshDashboardData();
    });
    $('#app-main').on('click', '#dash-next', function () {
      if (dashboardListState.lastSeenId == null) return;
      if (!dashboardListState.prevStack) dashboardListState.prevStack = [];
      dashboardListState.prevStack.push(dashboardListState.afterId);
      dashboardListState.afterId = dashboardListState.lastSeenId;
      refreshDashboardData();
    });
    if (!dashboardListState.prevStack) dashboardListState.prevStack = [];
    renderDashboardPager();

    // Render from cache if available; otherwise show an empty state.
    if (dashboardCache.html) {
      $('#dash-sync').removeClass('syncing').text('Idle');
      $('#dash-updated').text(dashboardCache.updatedText || '—');
      $('#dash-body').html(dashboardCache.html);
    } else {
      // First time in this browser session: fetch once to populate the snapshot.
      refreshDashboardData();
    }
  }

  function renderDevicesPager() {
    const total = Number(devicesListState.total) || 0;
    const size = Number(devicesListState.size) || 100;
    const page = Number(devicesListState.page) || 0;
    const from = total === 0 ? 0 : page * size + 1;
    const to = total === 0 ? 0 : Math.min(total, page * size + size);
    const lastPage = total === 0 ? 0 : Math.max(0, Math.ceil(total / size) - 1);
    const atStart = page <= 0;
    const atEnd = page >= lastPage;
    $('#devices-pager').html(
      '<div class="pager-left">' +
        '<span class="pager-meta">Showing <strong>' +
        from +
        '</strong>–<strong>' +
        to +
        '</strong> of <strong>' +
        total +
        '</strong></span>' +
        '</div>' +
        '<div class="pager-right">' +
        '<button type="button" class="btn small ghost" id="devices-prev" ' +
        (atStart ? 'disabled' : '') +
        '>Prev</button>' +
        '<button type="button" class="btn small ghost" id="devices-next" ' +
        (atEnd ? 'disabled' : '') +
        '>Next</button>' +
        '</div>',
    );
  }

  function loadDevicesSummary(opts) {
    opts = opts || {};
    const q = (devicesListState.q || '').trim();
    const params = {
      page: String(devicesListState.page || 0),
      size: String(devicesListState.size || 100),
      includeTotal: String(opts.includeTotal !== false),
    };
    if (q) params.q = q;

    $('#devices-tbody').html('<tr><td colspan="5" class="muted">Loading…</td></tr>');
    $.getJSON(API + '/devices/summary', params)
      .done(function (r) {
        const nextTotal = r && r.total != null ? Number(r.total) : NaN;
        if (Number.isFinite(nextTotal) && nextTotal >= 0) {
          devicesListState.total = nextTotal;
        } else if (!devicesListState.total) {
          devicesListState.total = Array.isArray(r.devices) ? r.devices.length : 0;
        }
        devicesListState.page = Number(r.page) || 0;
        devicesListState.size = Number(r.size) || devicesListState.size;
        let html = '';
        (r.devices || []).forEach(function (d) {
          html +=
            '<tr><td>' +
            escapeHtml(d.serialNumber) +
            '</td><td>' +
            escapeHtml(d.firmwareVersion) +
            '</td><td>' +
            fmtTime(d.registrationDate) +
            '</td><td>' +
            (d.enabled ? 'Active' : 'Disabled') +
            '</td><td><a class="btn" href="#devices/' +
            d.id +
            '">Details</a></td></tr>';
        });
        if (!html) html = '<tr><td colspan="5" class="muted">No devices.</td></tr>';
        $('#devices-tbody').html(html);
        if (r.searchNote) {
          $('#devices-note').text(r.searchNote).removeAttr('hidden');
        } else {
          $('#devices-note').attr('hidden', 'hidden').text('');
        }
        renderDevicesPager();
      })
      .fail(function () {
        devicesListState.total = 0;
        renderDevicesPager();
        $('#devices-tbody').html('<tr><td colspan="5" class="err">Request failed</td></tr>');
      });
  }

  function renderDevices() {
    $('#app-main').html(
      '<div class="wrap"><h1>Devices</h1>' +
        '<form class="row" id="devices-form">' +
        '<label for="devices-q">Serial (exact)</label>' +
        '<input id="devices-q" type="text" />' +
        '<button type="submit" class="btn">Search</button>' +
        '<button type="button" class="btn ghost" id="devices-clear">Clear</button></form>' +
        '<p id="devices-note" class="note" hidden></p>' +
        '<div class="pager" id="devices-pager"></div>' +
        '<div class="table-wrap"><table><thead><tr><th>Serial</th><th>Firmware</th><th>Registered</th><th>Status</th><th></th></tr></thead><tbody id="devices-tbody"></tbody></table></div></div>',
    );
    $('#devices-form').on('submit', function (e) {
      e.preventDefault();
      devicesListState.q = String($('#devices-q').val() || '').trim();
      devicesListState.page = 0;
      loadDevicesSummary({ includeTotal: true });
    });
    $('#devices-clear').on('click', function () {
      $('#devices-q').val('');
      devicesListState.q = '';
      devicesListState.page = 0;
      loadDevicesSummary({ includeTotal: true });
    });

    $('#app-main').on('click', '#devices-prev', function () {
      if (devicesListState.page <= 0) return;
      devicesListState.page = Math.max(0, (devicesListState.page || 0) - 1);
      loadDevicesSummary({ includeTotal: true });
    });
    $('#app-main').on('click', '#devices-next', function () {
      const total = Number(devicesListState.total) || 0;
      const size = Number(devicesListState.size) || 100;
      const lastPage = total === 0 ? 0 : Math.max(0, Math.ceil(total / size) - 1);
      if ((devicesListState.page || 0) >= lastPage) return;
      devicesListState.page = Math.min(lastPage, (devicesListState.page || 0) + 1);
      loadDevicesSummary({ includeTotal: true });
    });

    devicesListState.q = '';
    devicesListState.page = 0;
    devicesListState.size = 100;
    devicesListState.total = 0;
    renderDevicesPager();
    loadDevicesSummary({ includeTotal: true });
  }

  function parseSeriesValue(v) {
    if (v == null || v === '') return NaN;
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    const x = Number(v);
    if (Number.isFinite(x)) return x;
    const f = parseFloat(String(v).trim().replace(',', '.'));
    return Number.isFinite(f) ? f : NaN;
  }

  function parseSeriesTime(t) {
    const ms = new Date(t).getTime();
    return Number.isFinite(ms) ? ms : NaN;
  }

  function parseSeriesPoints(pts) {
    const out = [];
    (pts || []).forEach(function (p) {
      const tm = parseSeriesTime(p.t);
      const val = parseSeriesValue(p.v);
      if (Number.isFinite(tm) && Number.isFinite(val)) {
        out.push({ t: tm, v: val });
      }
    });
    out.sort(function (a, b) {
      return a.t - b.t;
    });
    return out;
  }

  /** Min/max stats for captions (raw point count, no synthetic extra point). */
  function analyzeSeries(parsed) {
    if (!parsed || parsed.length === 0) {
      return null;
    }
    const minT = Math.min.apply(
      null,
      parsed.map(function (p) {
        return p.t;
      }),
    );
    const maxT = Math.max.apply(
      null,
      parsed.map(function (p) {
        return p.t;
      }),
    );
    const minV = Math.min.apply(
      null,
      parsed.map(function (p) {
        return p.v;
      }),
    );
    const maxV = Math.max.apply(
      null,
      parsed.map(function (p) {
        return p.v;
      }),
    );
    const span = maxV - minV;
    const flatValue = span < 1e-12;
    return {
      minT: minT,
      maxT: maxT,
      minV: minV,
      maxV: maxV,
      flatValue: flatValue,
      count: parsed.length,
    };
  }

  function yAxisTitleForSensor(sensorKey) {
    if (sensorKey === 'temperature') {
      return 'Temperature (°C)';
    }
    if (sensorKey === 'humidity') {
      return 'Relative humidity (%)';
    }
    if (sensorKey === 'co') {
      return 'Carbon monoxide (PPM)';
    }
    return 'Value';
  }

  /** Median time between consecutive points (ms); used to detect “same burst” vs offline gap. */
  function medianStepMs(parsed) {
    if (!parsed || parsed.length < 2) {
      return 60000;
    }
    const deltas = [];
    for (let i = 1; i < parsed.length; i++) {
      const d = parsed[i].t - parsed[i - 1].t;
      if (d > 0) {
        deltas.push(d);
      }
    }
    if (!deltas.length) {
      return 60000;
    }
    deltas.sort(function (a, b) {
      return a - b;
    });
    const m = deltas[Math.floor(deltas.length / 2)];
    return Number.isFinite(m) && m > 0 ? m : 60000;
  }

  /**
   * Longer gaps than this are not drawn as a line (device offline, then bulk upload with older
   * {@code recordedAt} timestamps). Points still appear; only the connector is hidden.
   */
  function maxLineGapSpanMs(parsed) {
    const tenMin = 10 * 60 * 1000;
    const med = medianStepMs(parsed);
    const floor = 6 * 60 * 1000;
    const ceiling = 6 * 60 * 60 * 1000;
    if (med < tenMin) {
      return Math.min(45 * 60 * 1000, Math.max(floor, med * 15));
    }
    return Math.min(ceiling, Math.max(floor, med * 1.45));
  }

  function segmentSpansOfflineGap(ctx, maxGapMs) {
    const x0 = ctx.p0.parsed.x;
    const x1 = ctx.p1.parsed.x;
    if (!Number.isFinite(x0) || !Number.isFinite(x1)) {
      return false;
    }
    return Math.abs(x1 - x0) > maxGapMs;
  }

  function createLineChart(canvas, parsed, strokeColor, sensorKey, compact) {
    if (typeof Chart === 'undefined') {
      return null;
    }
    const yTitle = yAxisTitleForSensor(sensorKey);
    const data = parsed.map(function (p) {
      return { x: p.t, y: p.v };
    });
    const existing = Chart.getChart(canvas);
    if (existing) {
      existing.destroy();
    }
    const tickColor = 'rgba(71, 85, 105, 0.85)';
    const gridColor = 'rgba(148, 163, 184, 0.28)';
    const u = unitForSensor(sensorKey);
    const minV = Math.min.apply(
      null,
      parsed.map(function (p) {
        return p.v;
      }),
    );
    const maxV = Math.max.apply(
      null,
      parsed.map(function (p) {
        return p.v;
      }),
    );
    const flatY = maxV - minV < 1e-12;
    /** Y-axis always includes zero (origin-style), not auto-zoomed to series min. */
    const yScaleExtra = Object.assign(
      { min: 0 },
      flatY && Number.isFinite(maxV)
        ? {
            max: Math.max(maxV * 1.12, maxV + 0.01, 1),
          }
        : {},
    );
    const minT = Math.min.apply(
      null,
      parsed.map(function (p) {
        return p.t;
      }),
    );
    const maxT = Math.max.apply(
      null,
      parsed.map(function (p) {
        return p.t;
      }),
    );
    const narrowTime = !Number.isFinite(minT) || maxT - minT < 120000;
    const xScaleExtra =
      narrowTime && Number.isFinite(minT) && Number.isFinite(maxT)
        ? { min: minT - 120000, max: maxT + 120000 }
        : {};
    const maxGapMs = maxLineGapSpanMs(parsed);
    const fillRgb = strokeColor + '22';
    return new Chart(canvas, {
      type: 'line',
      data: {
        datasets: [
          {
            label: yTitle,
            data: data,
            borderColor: strokeColor,
            backgroundColor: fillRgb,
            fill: compact,
            tension: 0.15,
            pointRadius: parsed.length === 1 ? 5 : compact ? 0 : 2,
            pointHoverRadius: 6,
            borderWidth: 2,
            spanGaps: false,
            segment: {
              borderColor: function (ctx) {
                return segmentSpansOfflineGap(ctx, maxGapMs) ? 'transparent' : strokeColor;
              },
              backgroundColor: function (ctx) {
                if (!compact) {
                  return undefined;
                }
                return segmentSpansOfflineGap(ctx, maxGapMs) ? 'transparent' : fillRgb;
              },
            },
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        layout: {
          padding: {
            bottom: compact ? 2 : 26,
          },
        },
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          subtitle: compact
            ? { display: false }
            : {
                display: true,
                position: 'bottom',
                text:
                  'Total: ' +
                  parsed.length +
                  ' sensor ' +
                  (parsed.length === 1 ? 'row' : 'rows') +
                  ' in this range',
                color: tickColor,
                font: { size: 12, weight: '600' },
                padding: { top: 6 },
              },
          tooltip: {
            callbacks: {
              title: function (items) {
                const it = items[0];
                if (!it || !it.parsed) return '';
                const ms = it.parsed.x;
                return Number.isFinite(ms) ? new Date(ms).toLocaleString() : '';
              },
              label: function (ctx) {
                const v = ctx.parsed.y;
                const num = Number.isFinite(v) ? Number(v).toFixed(2) : String(v);
                return (u ? num + ' ' + u : num);
              },
            },
          },
        },
        scales: {
          x: Object.assign(
            {
              type: 'time',
              display: true,
              title: {
                display: true,
                text: 'Time',
                color: tickColor,
                font: { size: compact ? 10 : 12, weight: '600' },
              },
              grid: { color: gridColor },
              ticks: {
                color: tickColor,
                font: { size: compact ? 9 : 11 },
                maxRotation: compact ? 50 : 40,
                autoSkip: true,
                maxTicksLimit: compact ? 5 : 14,
              },
            },
            xScaleExtra,
          ),
          y: Object.assign(
            {
              display: true,
              title: {
                display: true,
                text: yTitle,
                color: tickColor,
                font: { size: compact ? 10 : 12, weight: '600' },
              },
              grid: { color: gridColor },
              ticks: {
                color: tickColor,
                font: { size: compact ? 9 : 11 },
                maxTicksLimit: compact ? 5 : 12,
              },
            },
            yScaleExtra,
          ),
        },
      },
    });
  }

  function mountDeviceCharts(deviceId) {
    if (typeof Chart === 'undefined') {
      return;
    }
    const idStr = String(deviceId);
    ['temperature', 'humidity', 'co'].forEach(function (sensorKey) {
      const el = document.getElementById('chart-canvas-' + idStr + '-' + sensorKey);
      if (!el) {
        return;
      }
      const parsed = parseSeriesPoints(deviceChartSeriesCache[sensorKey]);
      if (!parsed.length) {
        return;
      }
      const stroke =
        sensorKey === 'temperature' ? '#0369a1' : sensorKey === 'humidity' ? '#0d9488' : '#c2410c';
      const chart = createLineChart(el, parsed, stroke, sensorKey, true);
      if (chart) {
        deviceInlineCharts[sensorKey] = chart;
      }
    });
  }

  /** Value-only line; row count is shown separately as “Total: … rows”. */
  function statsCaption(geo, unit) {
    const n = geo.count;
    const u = unit ? ' ' + unit : '';
    if (geo.flatValue) {
      return 'Steady at ' + fmtNum(geo.minV) + u + ' · ' + n + ' ' + (n === 1 ? 'sample' : 'samples');
    }
    return 'Min ' + fmtNum(geo.minV) + u + ' · max ' + fmtNum(geo.maxV) + u;
  }

  function timeSpanCaption(minT, maxT) {
    if (!Number.isFinite(minT) || !Number.isFinite(maxT)) return '';
    return new Date(minT).toLocaleString() + ' — ' + new Date(maxT).toLocaleString();
  }

  function rangeLabelForKey(rangeKey) {
    for (let i = 0; i < RANGE_OPTIONS.length; i++) {
      if (RANGE_OPTIONS[i].key === rangeKey) {
        return RANGE_OPTIONS[i].label;
      }
    }
    return rangeKey;
  }

  function unitForSensor(sensorKey) {
    if (sensorKey === 'temperature') return '°C';
    if (sensorKey === 'humidity') return '%';
    if (sensorKey === 'co') return 'PPM';
    return '';
  }

  function makeChartCard(deviceId, sensorKey, title, pts, stroke) {
    const parsed = parseSeriesPoints(pts);
    const geo = analyzeSeries(parsed);
    if (!geo) {
      return '';
    }
    const unit = unitForSensor(sensorKey);
    const stats = statsCaption(geo, unit);
    const rowCount = Array.isArray(pts) ? pts.length : 0;
    const canvasId = 'chart-canvas-' + String(deviceId) + '-' + sensorKey;
    return (
      '<figure class="chart-card" data-chart-sensor="' +
      escapeHtml(sensorKey) +
      '">' +
      '<div class="chart-card-head">' +
      '<figcaption>' +
      escapeHtml(title) +
      '</figcaption>' +
      '<button type="button" class="btn small chart-enlarge" data-chart-sensor="' +
      escapeHtml(sensorKey) +
      '" data-chart-title="' +
      escapeHtml(title) +
      '" data-chart-stroke="' +
      stroke +
      '" title="Open larger chart">Enlarge</button>' +
      '</div>' +
      '<p class="chart-stats chart-row-total muted"><strong>Total:</strong> ' +
      rowCount +
      ' sensor ' +
      (rowCount === 1 ? 'row' : 'rows') +
      ' in this range</p>' +
      '<p class="chart-stats muted">' +
      escapeHtml(stats) +
      '</p>' +
      '<div class="chart-canvas-wrap"><canvas id="' +
      escapeHtml(canvasId) +
      '" width="400" height="200" aria-label="' +
      escapeHtml(title) +
      ' over time"></canvas></div>' +
      '</figure>'
    );
  }

  function closeChartModal() {
    destroyModalChart();
    $('#chart-modal-root').attr('hidden', 'hidden');
    $('#chart-modal-inner').empty();
  }

  function openChartEnlarge(sensorKey, title, stroke) {
    const pts = deviceChartSeriesCache[sensorKey] || [];
    const parsed = parseSeriesPoints(pts);
    const geo = analyzeSeries(parsed);
    if (!geo) {
      return;
    }
    const unit = unitForSensor(sensorKey);
    const rangeLbl = rangeLabelForKey(deviceViewState.range || 'today');
    const rowCount = Array.isArray(pts) ? pts.length : 0;
    const sub =
      rangeLbl +
      ' · ' +
      timeSpanCaption(geo.minT, geo.maxT) +
      ' · ' +
      statsCaption(geo, unit) +
      ' · Total: ' +
      rowCount +
      ' sensor row' +
      (rowCount === 1 ? '' : 's') +
      ' in this range';
    $('#chart-modal-title').text(title);
    $('#chart-modal-sub').text(sub);
    destroyModalChart();
    $('#chart-modal-inner').html(
      '<div class="modal-chart-canvas-wrap"><canvas id="chart-modal-canvas" width="900" height="400"></canvas></div>',
    );
    $('#chart-modal-root').removeAttr('hidden');
    const canvas = document.getElementById('chart-modal-canvas');
    if (canvas && typeof Chart !== 'undefined') {
      modalChartInstance = createLineChart(canvas, parsed, stroke, sensorKey, false);
    }
  }

  function rangeBarHtml(active) {
    let tabs = '';
    RANGE_OPTIONS.forEach(function (o) {
      const on = o.key === active;
      tabs +=
        '<button type="button" class="range-tab' +
        (on ? ' active' : '') +
        '" data-device-range="' +
        o.key +
        '" aria-selected="' +
        (on ? 'true' : 'false') +
        '">' +
        escapeHtml(o.label) +
        '</button>';
    });
    return '<div id="device-range-bar" class="device-range-bar">' + tabs + '</div>';
  }

  function loadDeviceSeriesOnly() {
    const id = deviceViewState.id;
    const r = deviceViewState.range;
    if (!id) return;
    destroyDeviceInlineCharts();
    $('#device-charts-wrap').html('<p class="muted chart-empty">Loading charts…</p>');
    $.when(
      $.getJSON(API + '/devices/' + id + '/series', { sensor: 'temperature', range: r }),
      $.getJSON(API + '/devices/' + id + '/series', { sensor: 'humidity', range: r }),
      $.getJSON(API + '/devices/' + id + '/series', { sensor: 'co', range: r }),
    )
      .done(function (a, b, c) {
        const tPts = a[0];
        const hPts = b[0];
        const cPts = c[0];
        deviceChartSeriesCache.temperature = tPts || [];
        deviceChartSeriesCache.humidity = hPts || [];
        deviceChartSeriesCache.co = cPts || [];
        const charts =
          makeChartCard(id, 'temperature', 'Temperature °C', tPts, '#0369a1') +
          makeChartCard(id, 'humidity', 'Humidity %', hPts, '#0d9488') +
          makeChartCard(id, 'co', 'CO (PPM)', cPts, '#c2410c');
        if (!charts) {
          $('#device-charts-wrap').html(
            '<p class="muted chart-empty">No samples between the start of this period and <strong>now</strong> (server clock). ' +
              'Timestamps after now are excluded. If your test data is from a past year (e.g. 2025), switch the range to <strong>All time</strong>.</p>',
          );
        } else {
          $('#device-charts-wrap').html('<div class="charts">' + charts + '</div>');
          mountDeviceCharts(id);
        }
      })
      .fail(function () {
        $('#device-charts-wrap').html('<p class="muted chart-empty">Could not load series.</p>');
      });
  }

  function loadDeviceDetail(id, opts) {
    opts = opts || {};
    destroyDeviceInlineCharts();
    closeChartModal();
    deviceViewState.id = id;
    if (!opts.preserveRange) {
      try {
        deviceViewState.range = validDeviceRange(localStorage.getItem(DEVICE_CHART_RANGE_KEY) || 'today');
      } catch (e) {
        deviceViewState.range = 'today';
      }
    }
    $('#app-main').html(
      '<div class="wrap"><p><a href="#devices">← Devices</a></p><div id="device-detail-root"></div></div>',
    );
    $.getJSON(API + '/devices/' + id + '/detail')
      .done(function (d) {
        const dev = d.device;
        let readings = '';
        (d.recentReadings || []).forEach(function (row) {
          readings +=
            '<tr><td>' +
            fmtTime(row.recordedAt) +
            '</td><td>' +
            fmtNum(row.temperatureCelsius) +
            '</td><td>' +
            fmtNum(row.humidityPercent) +
            '</td><td>' +
            fmtNum(row.carbonMonoxidePpm) +
            '</td><td>' +
            escapeHtml(row.healthStatus || '—') +
            '</td></tr>';
        });
        if (!readings) readings = '<tr><td colspan="5" class="muted">No samples.</td></tr>';
        $('#device-detail-root').html(
          '<h1>' +
            escapeHtml(dev.serialNumber) +
            '</h1>' +
            '<p class="muted">Firmware ' +
            escapeHtml(dev.firmwareVersion) +
            ' · ' +
            (dev.enabled ? 'Active' : 'Disabled') +
            '</p>' +
            '<p><button type="button" class="btn device-simulate-notify" data-device-id="' +
            id +
            '">Simulate notifications (test)</button></p>' +
            '<h2>Trends</h2>' +
            '<p class="alert-hint">Admin alerts when CO is over 9 PPM (max in the ingest batch) appear under ' +
            '<a href="#notifications">Notifications</a> and on the nav badge — not on this chart.</p>' +
            '<p class="alert-hint">When a unit was offline and later uploads a backlog, every sample is stored and shown. Long pauses between <code>recordedAt</code> timestamps are not connected by a line so the chart does not imply a false ramp.</p>' +
            rangeBarHtml(deviceViewState.range) +
            '<div id="device-charts-wrap"></div>' +
            '<h2>Recent readings</h2>' +
            '<div class="table-wrap"><table><thead><tr><th>Time</th><th>Temp °C</th><th>Humidity %</th><th>CO</th><th>Health</th></tr></thead><tbody>' +
            readings +
            '</tbody></table></div>',
        );
        loadDeviceSeriesOnly();
      })
      .fail(function () {
        $('#device-detail-root').html('<p class="err">Device not found or access denied.</p>');
      });
  }

  function renderOpenNotificationsPager() {
    const size = Number(openNotificationsState.size) || 100;
    const page = Number(openNotificationsState.page) || 0;
    const atStart = page <= 0;
    const atEnd = openNotificationsState.hasMore !== true;
    $('#notify-pager').html(
      '<div class="pager-left">' +
        '<span class="pager-meta">Page <strong>' +
        (page + 1) +
        '</strong> · Showing <strong>' +
        size +
        '</strong> per page</span>' +
        '</div>' +
        '<div class="pager-right">' +
        '<button type="button" class="btn small ghost" id="notify-prev" ' +
        (atStart ? 'disabled' : '') +
        '>Prev</button>' +
        '<button type="button" class="btn small ghost" id="notify-next" ' +
        (atEnd ? 'disabled' : '') +
        '>Next</button>' +
        '</div>',
    );
  }

  function loadOpenNotificationsPage() {
    const params = {
      page: String(openNotificationsState.page || 0),
      size: String(openNotificationsState.size || 100),
    };
    $('#notify-tbody').html('<tr><td colspan="5" class="muted">Loading…</td></tr>');
    $.getJSON(API + '/notifications/unresolved-page', params)
      .done(function (r) {
        const rows = (r && r.notifications) || [];
        openNotificationsState.page = Number(r.page) || 0;
        openNotificationsState.size = Number(r.size) || openNotificationsState.size;
        openNotificationsState.hasMore = r && r.hasMore === true;
        let html = '';
        (rows || []).forEach(function (n) {
          const serial = n.deviceSerialNumber ? n.deviceSerialNumber : '—';
          html +=
            '<tr data-notify-row="' +
            escapeHtml(String(n.id)) +
            '"><td>' +
            fmtTime(n.createdAt) +
            '</td><td>' +
            escapeHtml(String(n.type)) +
            '</td><td>' +
            escapeHtml(serial) +
            '</td><td>' +
            escapeHtml(n.message || '') +
            '</td><td><button type="button" class="btn small notify-resolve" data-id="' +
            n.id +
            '">Resolve</button></td></tr>';
        });
        if (!html) html = '<tr><td colspan="5" class="muted">No open notifications.</td></tr>';
        $('#notify-tbody').html(html);
        renderOpenNotificationsPager();
      })
      .fail(function () {
        openNotificationsState.hasMore = false;
        renderOpenNotificationsPager();
        $('#notify-tbody').html('<tr><td colspan="5" class="err">Request failed</td></tr>');
      });
  }

  function renderNotifications() {
    $('#app-main').html(
      '<div class="wrap"><h1>Notifications</h1>' +
        '<p class="muted notify-intro">Open items are shown to every admin. Resolving removes the row for <strong>all</strong> admins. Alerts are raised when carbon monoxide is above 9 PPM (max in the ingest batch).</p>' +
        '<div class="row" style="gap:8px; align-items:center; margin: 10px 0 12px 0">' +
        '<span style="flex:1"></span>' +
        '<button type="button" class="btn" id="notify-refresh">Refresh</button>' +
        '</div>' +
        '<div class="pager" id="notify-pager"></div>' +
        '<div class="table-wrap"><table><thead id="notify-thead"></thead><tbody id="notify-tbody"></tbody></table></div></div>',
    );
    $('#notify-thead').html('<tr><th>Created</th><th>Type</th><th>Device</th><th>Message</th><th></th></tr>');
    $('#notify-refresh').on('click', function () {
      loadOpenNotificationsPage();
    });

    $('#app-main').on('click', '.notify-resolve', function () {
      const nid = $(this).data('id');
      // Optimistic UI: remove immediately
      const sel = 'tr[data-notify-row="' + String(nid) + '"]';
      $(sel).remove();
      $.post(API + '/notifications/' + nid + '/resolve').always(function () {
        // If we removed the last row on this page, reload (and clamp page to >= 0).
        if ($('#notify-tbody tr').length === 0) {
          openNotificationsState.page = Math.max(0, openNotificationsState.page || 0);
          loadOpenNotificationsPage();
        }
        fetchOpenCount();
      });
    });

    $('#app-main').off('click', '#notify-prev');
    $('#app-main').off('click', '#notify-next');
    $('#app-main').on('click', '#notify-prev', function () {
      if (openNotificationsState.page <= 0) return;
      openNotificationsState.page = Math.max(0, (openNotificationsState.page || 0) - 1);
      loadOpenNotificationsPage();
    });
    $('#app-main').on('click', '#notify-next', function () {
      if (openNotificationsState.hasMore !== true) return;
      openNotificationsState.page = Math.max(0, (openNotificationsState.page || 0) + 1);
      loadOpenNotificationsPage();
    });

    openNotificationsState.page = 0;
    openNotificationsState.size = 100;
    openNotificationsState.hasMore = false;
    loadOpenNotificationsPage();
  }

  function loadAdmins() {
    $.getJSON(API + '/admins').done(function (rows) {
      let html = '';
      (rows || []).forEach(function (u) {
        let status = 'Disabled';
        if (u.blocked) status = 'Blocked';
        else if (u.enabled) status = 'Active';
        const btn = u.blocked
          ? '<button type="button" class="btn small admin-block" data-id="' +
            u.id +
            '" data-blocked="false">Unblock</button>'
          : '<button type="button" class="btn small admin-block" data-id="' +
            u.id +
            '" data-blocked="true">Block</button>';
        html +=
          '<tr><td>' +
          escapeHtml(u.email) +
          '</td><td>' +
          fmtTime(u.createdAt) +
          '</td><td>' +
          status +
          '</td><td>' +
          btn +
          '</td></tr>';
      });
      if (!html) html = '<tr><td colspan="4" class="muted">No admins.</td></tr>';
      $('#admins-tbody').html(html);
    });
  }

  function renderAdmins() {
    $('#app-main').html(
      '<div class="wrap"><h1>Administrators</h1>' +
        '<button type="button" class="btn" id="admins-refresh">Refresh</button>' +
        '<div class="table-wrap"><table><thead><tr><th>Email</th><th>Created</th><th>Status</th><th></th></tr></thead><tbody id="admins-tbody"></tbody></table></div></div>',
    );
    $('#admins-refresh').on('click', loadAdmins);
    $('#app-main').on('click', '.admin-block', function () {
      const aid = $(this).data('id');
      const blocked = $(this).data('blocked') === true || $(this).data('blocked') === 'true';
      $.post(API + '/admins/' + aid + '/blocked?blocked=' + encodeURIComponent(String(blocked))).always(loadAdmins);
    });
    loadAdmins();
  }

  function renderInvitations() {
    $('#app-main').html(
      '<div class="wrap"><h1>Invitations</h1>' +
        '<p class="muted">Optional email hint is stored with the invite (not validated as unique).</p>' +
        '<form class="form" id="inv-form">' +
        '<label for="inv-hint">Email hint</label>' +
        '<input id="inv-hint" type="text" placeholder="colleague@company.com" />' +
        '<button type="submit" class="btn" id="inv-submit">Create invite link</button></form>' +
        '<div id="inv-result" style="display:none" class="result"><p>Share this link (one-time registration):</p><textarea readonly rows="3" id="inv-ta"></textarea></div>' +
        '<p id="inv-err" class="err" style="display:none"></p></div>',
    );
    $('#inv-form').on('submit', function (e) {
      e.preventDefault();
      $('#inv-err').hide();
      const hint = $('#inv-hint').val();
      $.ajax({
        url: API + '/invitations',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ emailHint: hint ? String(hint) : null }),
      })
        .done(function (r) {
          $('#inv-ta').val(r.inviteLink || '');
          $('#inv-result').show();
        })
        .fail(function () {
          $('#inv-err').text('Could not create invitation').show();
        });
    });
  }

  function render() {
    const route = parseRoute();
    setNavActive(route);
    if (route.name !== 'device') {
      destroyDeviceInlineCharts();
      closeChartModal();
    }
    if (route.name === 'dashboard') loadDashboard();
    else if (route.name === 'devices') renderDevices();
    else if (route.name === 'device') loadDeviceDetail(route.id, {});
    else if (route.name === 'notifications') renderNotifications();
    else if (route.name === 'admins') renderAdmins();
    else if (route.name === 'invitations') renderInvitations();
  }

  async function logout() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    const t = m ? decodeURIComponent(m[1]) : '';
    await fetch('/logout', {
      method: 'POST',
      credentials: 'include',
      headers: t ? { 'X-XSRF-TOKEN': t } : {},
    });
    window.location.href = '/login?logout';
  }

  $(function () {
    if (!location.hash) location.hash = '#dashboard';
    fetchOpenCount();
    render();
    connectSse();

    $(window).on('hashchange', function () {
      render();
    });

    // Do not refresh pages automatically on tab focus.

    $('#btn-logout').on('click', function () {
      logout();
    });

    $(document).on('click', '[data-device-range]', function (e) {
      e.preventDefault();
      const next = $(this).attr('data-device-range');
      if (!next || next === deviceViewState.range) return;
      deviceViewState.range = validDeviceRange(next);
      try {
        localStorage.setItem(DEVICE_CHART_RANGE_KEY, deviceViewState.range);
      } catch (err) {
        /* ignore */
      }
      $('#device-range-bar .range-tab').removeClass('active').attr('aria-selected', 'false');
      $(this).addClass('active').attr('aria-selected', 'true');
      loadDeviceSeriesOnly();
    });

    $(document).on('click', '.device-simulate-notify', function (e) {
      e.preventDefault();
      const id = $(this).attr('data-device-id') || deviceViewState.id;
      const $btn = $(this);
      $btn.prop('disabled', true);
      $.post(API + '/devices/' + id + '/simulate-notifications')
        .done(function () {
          showNotificationToast('Test notifications created. Open Notifications to review or resolve.');
          loadDeviceDetail(id, { preserveRange: true });
          fetchOpenCount();
        })
        .fail(function () {
          showNotificationToast('Simulation failed. Is the device enabled and are you still logged in as admin?');
        })
        .always(function () {
          $btn.prop('disabled', false);
        });
    });

    $(document).on('click', '.chart-enlarge', function (e) {
      e.preventDefault();
      const sensor = $(this).attr('data-chart-sensor');
      const ttl = $(this).attr('data-chart-title') || 'Trend';
      const stroke = $(this).attr('data-chart-stroke') || '#0369a1';
      if (!sensor) return;
      openChartEnlarge(sensor, ttl, stroke);
    });

    $('#chart-modal-close, #chart-modal-backdrop').on('click', function () {
      closeChartModal();
    });

    $(document).on('keydown', function (e) {
      if (e.key !== 'Escape') return;
      const root = document.getElementById('chart-modal-root');
      if (root && !root.hasAttribute('hidden')) {
        closeChartModal();
      }
    });
  });
})();
