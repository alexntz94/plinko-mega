// Live RTP and probability totals for the odds editor. The server validates again on proposal and on approval.
(function () {
    function parse(value) {
        var number = Number(String(value).trim());
        return String(value).trim() === '' || isNaN(number) ? null : number;
    }

    function recalc(form) {
        var multipliers = form.querySelectorAll('input[name="multiplier"]');
        var probabilities = form.querySelectorAll('input[name="probability"]');
        var shares = form.querySelectorAll('td.share');
        var totalProbability = 0, rtp = 0, valid = true;

        for (var i = 0; i < multipliers.length; i++) {
            var m = parse(multipliers[i].value), p = parse(probabilities[i].value);
            multipliers[i].classList.toggle('invalid', m === null || m < 0);
            probabilities[i].classList.toggle('invalid', p === null || p < 0);
            if (m === null || p === null || m < 0 || p < 0) {
                valid = false;
                shares[i].textContent = '–';
                continue;
            }
            totalProbability += p;
            rtp += m * p;
            shares[i].textContent = (m * p).toFixed(3) + '%';
        }

        var minRtp = Number(form.dataset.minRtp), maxRtp = Number(form.dataset.maxRtp);
        form.querySelector('.total-probability').textContent = totalProbability.toFixed(6) + '%';
        form.querySelector('.rtp').textContent = rtp.toFixed(3) + '%';

        var message = form.querySelector('.validation');
        var problems = [];
        if (!valid) problems.push('Every value must be a number, 0 or more.');
        if (Math.abs(totalProbability - 100) > 0.0001) problems.push('Probabilities must add up to 100%.');
        if (rtp > maxRtp) problems.push('RTP is above the hard limit of ' + maxRtp + '%.');
        if (rtp < minRtp) problems.push('RTP is below the hard limit of ' + minRtp + '%.');
        message.textContent = problems.length ? problems.join(' ') : 'Valid.';
        message.classList.toggle('ok', problems.length === 0);
    }

    function fill(form, name, csv) {
        var values = csv.split(',');
        form.querySelectorAll('input[name="' + name + '"]').forEach(function (input, i) {
            input.value = values[i];
        });
        recalc(form);
    }

    document.querySelectorAll('form.odds-form').forEach(function (form) {
        form.addEventListener('input', function () { recalc(form); });
        var natural = form.querySelector('.load-natural');
        var defaults = form.querySelector('.load-defaults');
        if (natural) natural.addEventListener('click', function () { fill(form, 'probability', form.dataset.natural); });
        if (defaults) defaults.addEventListener('click', function () { fill(form, 'multiplier', form.dataset.defaults); });
        recalc(form);
    });
})();
