(function (root) {
  const Campsite = root.Campsite || {};
  const { api, el, escapeHtml, showToast } = Campsite;

  function createCampgroundSearch() {
    const selectedCampgrounds = new Map();
    const expandedParks = new Map();
    let lastSearchResults = { parks: [], campgrounds: [] };
    let searchTimer = null;

    function init() {
      const input = el('campground-search');
      if (!input) return;

      input.addEventListener('input', e => {
        clearTimeout(searchTimer);
        const q = e.target.value.trim();
        if (q.length < 2) {
          el('search-results').classList.add('hidden');
          return;
        }
        searchTimer = setTimeout(() => doSearch(q), 300);
      });

      input.addEventListener('keydown', e => {
        if (e.key === 'Escape') el('search-results').classList.add('hidden');
      });

      document.addEventListener('click', e => {
        const searchWrap = input.closest('.search-wrap');
        if (searchWrap && !searchWrap.contains(e.target)) {
          el('search-results').classList.add('hidden');
        }
      });
    }

    async function doSearch(q) {
      try {
        const results = await api('GET', `/api/campsite/campgrounds/search?q=${encodeURIComponent(q)}`);
        lastSearchResults = results;
        renderSearchResults(results);
      } catch (err) {
        console.error('Search failed:', err);
      }
    }

    function rerenderDropdown() {
      renderSearchResults(lastSearchResults);
    }

    function renderSearchResults({ parks = [], campgrounds = [] }) {
      const container = el('search-results');
      container.innerHTML = '';

      if (!parks.length && !campgrounds.length) {
        container.innerHTML = '<div class="dropdown-item"><div class="dropdown-item-sub">No results found</div></div>';
        container.classList.remove('hidden');
        return;
      }

      if (parks.length) {
        appendSectionHeader(container, 'Parks & Recreation Areas');
        for (const park of parks) renderParkItem(container, park);
      }

      if (campgrounds.length) {
        if (parks.length) appendSectionHeader(container, 'Campgrounds');
        for (const cg of campgrounds) renderCampgroundItem(container, cg);
      }

      container.classList.remove('hidden');
    }

    function appendSectionHeader(container, text) {
      const h = document.createElement('div');
      h.className = 'dropdown-section-header';
      h.textContent = text;
      container.appendChild(h);
    }

    function renderParkItem(container, park) {
      const isExpanded = expandedParks.has(park.id);
      const state = expandedParks.get(park.id);
      const loc = [park.city, park.state].filter(Boolean).join(', ');

      const item = document.createElement('div');
      item.className = 'dropdown-item dropdown-item-park';
      item.innerHTML = `
        <div style="display:flex;align-items:center;gap:8px">
          <span class="park-chevron">${isExpanded ? '\u25bc' : '\u25b6'}</span>
          <div style="flex:1;min-width:0">
            <div class="dropdown-item-name">${escapeHtml(park.name)}</div>
            ${loc ? `<div class="dropdown-item-sub">${escapeHtml(loc)}</div>` : ''}
          </div>
        </div>
      `;
      item.addEventListener('click', e => { e.stopPropagation(); toggleParkExpand(park); });
      container.appendChild(item);

      if (isExpanded) {
        const campWrap = document.createElement('div');
        campWrap.className = 'park-campgrounds';
        if (!state?.campgrounds) {
          campWrap.innerHTML = '<div class="park-loading">Loading...</div>';
        } else if (state.campgrounds.length === 0) {
          campWrap.innerHTML = '<div class="park-loading">No campgrounds found in this area</div>';
        } else {
          for (const cg of state.campgrounds) renderParkCampgroundItem(campWrap, cg);
        }
        container.appendChild(campWrap);
      }
    }

    async function toggleParkExpand(park) {
      if (expandedParks.has(park.id)) {
        expandedParks.delete(park.id);
        rerenderDropdown();
        return;
      }
      expandedParks.set(park.id, { loading: true, campgrounds: null });
      rerenderDropdown();
      try {
        const campgrounds = await api('GET', `/api/campsite/campgrounds/in-park/${park.id}?name=${encodeURIComponent(park.name)}`);
        expandedParks.set(park.id, { loading: false, campgrounds });
        rerenderDropdown();
      } catch (err) {
        expandedParks.delete(park.id);
        showToast(`Failed to load campgrounds: ${err.message}`, 'error');
        rerenderDropdown();
      }
    }

    function renderParkCampgroundItem(container, cg) {
      const isSelected = selectedCampgrounds.has(cg.id);
      const item = document.createElement('div');
      item.className = 'park-campground-item';

      let meta = '';
      if (cg.reviews > 0) {
        const rStr = cg.reviews >= 1000 ? (cg.reviews / 1000).toFixed(1) + 'k' : cg.reviews;
        meta = `\u2605${cg.rating ? cg.rating.toFixed(1) : '?'} \u00b7 ${rStr} reviews`;
      }

      item.innerHTML = `
        <input type="checkbox" ${isSelected ? 'checked' : ''} style="pointer-events:none;accent-color:var(--accent)">
        <div class="park-campground-info">
          <div class="park-campground-name">${escapeHtml(cg.name)}</div>
          ${meta ? `<div class="park-campground-meta">${meta}</div>` : ''}
        </div>
      `;
      item.addEventListener('click', e => {
        e.stopPropagation();
        toggleCampground(cg);
        item.querySelector('input').checked = selectedCampgrounds.has(cg.id);
      });
      container.appendChild(item);
    }

    function renderCampgroundItem(container, cg) {
      const isSelected = selectedCampgrounds.has(cg.id);
      const item = document.createElement('div');
      item.className = `dropdown-item${isSelected ? ' selected' : ''}`;
      const sub = [cg.parent_name, cg.city, cg.state].filter(Boolean).join(' \u00b7 ');
      item.innerHTML = `
        <div style="display:flex;align-items:center;gap:8px">
          <div style="flex:1;min-width:0">
            <div class="dropdown-item-name">${escapeHtml(cg.name)}</div>
            <div class="dropdown-item-sub">${escapeHtml(sub)} &mdash; ID: ${cg.id}</div>
          </div>
          <span class="dropdown-item-add">${isSelected ? '\u2713' : '+'}</span>
        </div>
      `;
      item.addEventListener('click', () => {
        toggleCampground(cg);
        const sel = selectedCampgrounds.has(cg.id);
        item.classList.toggle('selected', sel);
        item.querySelector('.dropdown-item-add').textContent = sel ? '\u2713' : '+';
      });
      container.appendChild(item);
    }

    function toggleCampground(cg) {
      if (selectedCampgrounds.has(cg.id)) {
        selectedCampgrounds.delete(cg.id);
      } else {
        selectedCampgrounds.set(cg.id, cg);
      }
      renderSelectedCampgrounds();
    }

    function renderSelectedCampgrounds() {
      const container = el('selected-campgrounds');
      container.innerHTML = '';
      for (const [id, cg] of selectedCampgrounds) {
        const chip = document.createElement('div');
        chip.className = 'campground-chip';
        chip.innerHTML = `
          <span>${escapeHtml(cg.name)} <span style="color:var(--text3)">#${id}</span></span>
          <button type="button" title="Remove">\u2715</button>
        `;
        chip.querySelector('button').addEventListener('click', () => {
          selectedCampgrounds.delete(id);
          renderSelectedCampgrounds();
          rerenderDropdown();
        });
        container.appendChild(chip);
      }
    }

    function selectCampground(cg) {
      if (!cg?.id) return;
      selectedCampgrounds.set(String(cg.id), cg);
      renderSelectedCampgrounds();
    }

    function bestCampgroundResult(results, id) {
      const campgrounds = Array.isArray(results?.campgrounds) ? results.campgrounds : [];
      return campgrounds.find(cg => String(cg.id) === String(id)) || campgrounds[0] || null;
    }

    async function prefillFromUrl() {
      const params = new URLSearchParams(window.location.search);
      const id = params.get('campground');
      if (!id || selectedCampgrounds.has(id)) return;

      try {
        const results = await api('GET', `/api/campsite/campgrounds/search?q=${encodeURIComponent(id)}`);
        const cg = bestCampgroundResult(results, id) || { id, name: `Campground ${id}` };
        selectCampground(cg);
        el('campground-search').value = cg.name || `Campground ${id}`;
      } catch (err) {
        const cg = { id, name: `Campground ${id}` };
        selectCampground(cg);
        el('campground-search').value = cg.name;
        showToast(`Could not resolve campground ${id}: ${err.message}`, 'error', 6000);
      }
    }

    function clear() {
      selectedCampgrounds.clear();
      expandedParks.clear();
      lastSearchResults = { parks: [], campgrounds: [] };
      renderSelectedCampgrounds();
      el('campground-search').value = '';
      el('search-results').classList.add('hidden');
      el('campground-search').focus();
    }

    return {
      selectedCampgrounds,
      clear,
      init,
      prefillFromUrl,
    };
  }

  root.Campsite = {
    ...Campsite,
    createCampgroundSearch,
  };
})(window);
