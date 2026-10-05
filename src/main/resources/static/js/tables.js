(function () {
  'use strict';

  function normalize(value) {
    return (value || '').replace(/\s+/g, ' ').trim().toLowerCase();
  }

  function valueForCell(cell) {
    if (!cell) {
      return '';
    }

    var value = cell.getAttribute('data-sort-value');
    if (value !== null) {
      return value;
    }

    return normalize(cell.textContent);
  }

  function compareValues(a, b, direction) {
    var left = normalize(a);
    var right = normalize(b);

    var leftNumber = Number(left.replace(/,/g, ''));
    var rightNumber = Number(right.replace(/,/g, ''));
    var bothNumbers = left !== '' && right !== '' &&
      Number.isFinite(leftNumber) && Number.isFinite(rightNumber);

    var result;
    if (bothNumbers) {
      result = leftNumber - rightNumber;
    } else {
      result = left.localeCompare(right, undefined, {
        numeric: true,
        sensitivity: 'base'
      });
    }

    return direction === 'desc' ? -result : result;
  }

  function addSortControls(table) {
    var headers = table.querySelectorAll('thead th[data-sortable="true"]');

    headers.forEach(function (header) {
      var label = normalize(header.textContent);
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'table-sort-button';
      button.setAttribute('aria-label', 'Sort by ' + label);
      button.innerHTML = '<span class="table-sort-icon" aria-hidden="true">↕</span>';
      header.appendChild(button);

      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();

        var currentColumn = table.querySelector('th[aria-sort]');
        var direction = 'asc';

        if (currentColumn === header && currentColumn.getAttribute('aria-sort') === 'ascending') {
          direction = 'desc';
        }

        table.querySelectorAll('thead th[aria-sort]').forEach(function (th) {
          th.removeAttribute('aria-sort');
          var icon = th.querySelector('.table-sort-icon');
          if (icon) {
            icon.textContent = '↕';
          }
        });

        header.setAttribute('aria-sort', direction === 'asc' ? 'ascending' : 'descending');
        button.setAttribute('aria-label',
          'Sort by ' + label + (direction === 'asc' ? ', ascending' : ', descending'));
        button.querySelector('.table-sort-icon').textContent = direction === 'asc' ? '↑' : '↓';

        sortTable(table, header, direction);
      });
    });
  }

  function sortTable(table, header, direction) {
    var body = table.tBodies[0];
    if (!body) {
      return;
    }

    var column = header.cellIndex;
    var rows = Array.prototype.slice.call(body.rows);
    var emptyRows = rows.filter(function (row) {
      return row.querySelector('.empty') !== null;
    });
    var dataRows = rows.filter(function (row) {
      return row.querySelector('.empty') === null;
    });

    dataRows.sort(function (a, b) {
      return compareValues(
        valueForCell(a.cells[column]),
        valueForCell(b.cells[column]),
        direction
      );
    });

    dataRows.forEach(function (row) {
      body.appendChild(row);
    });
    emptyRows.forEach(function (row) {
      body.appendChild(row);
    });
  }

  function addFilter(table) {
    var filterContainer = table.closest('.table-card')
      ? table.closest('.table-card').querySelector('.table-filter')
      : null;

    if (!filterContainer) {
      return;
    }

    var input = filterContainer.querySelector('input');
    if (!input) {
      return;
    }

    var columnSpec = table.getAttribute('data-filter-columns');
    var columns = columnSpec
      ? columnSpec.split(',').map(function (value) { return Number(value.trim()); })
      : null;

    input.addEventListener('input', function () {
      var query = normalize(input.value);
      var rows = table.tBodies[0] ? table.tBodies[0].rows : [];

      Array.prototype.forEach.call(rows, function (row) {
        if (row.querySelector('.empty')) {
          return;
        }

        var cells = Array.prototype.slice.call(row.cells);
        var searchable = columns
          ? columns.map(function (index) { return cells[index]; })
          : cells;

        var text = searchable
          .filter(Boolean)
          .map(function (cell) { return normalize(cell.textContent); })
          .join(' ');

        row.hidden = query !== '' && text.indexOf(query) === -1;
      });
    });
  }

  function initializeTable(table) {
    addSortControls(table);
    addFilter(table);
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('table[data-enhanced-table="true"]').forEach(initializeTable);
  });
}());
