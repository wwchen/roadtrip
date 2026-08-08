// Every ITU dial code, because a short list is not a shortcut — it is a wall.
// A user whose country is missing cannot enter their number at all, and there is
// no error message that helps them.
//
// [name, dial code, ISO 3166-1 alpha-2]. The ISO code is what makes this usable
// in a native <select>: a closed select is exactly as wide as its widest option,
// so labelling options "+1  United States" makes the code box swallow the field
// the phone number needs. "+1 US" reads the same, disambiguates the twenty
// countries that share +1, and keeps the box at the width of its own content.
export const DIAL_CODES = [
  ['Afghanistan','+93','AF'],['Albania','+355','AL'],['Algeria','+213','DZ'],['Andorra','+376','AD'],
  ['Angola','+244','AO'],['Argentina','+54','AR'],['Armenia','+374','AM'],['Aruba','+297','AW'],
  ['Australia','+61','AU'],['Austria','+43','AT'],['Azerbaijan','+994','AZ'],['Bahamas','+1','BS'],
  ['Bahrain','+973','BH'],['Bangladesh','+880','BD'],['Barbados','+1','BB'],['Belarus','+375','BY'],
  ['Belgium','+32','BE'],['Belize','+501','BZ'],['Benin','+229','BJ'],['Bermuda','+1','BM'],
  ['Bhutan','+975','BT'],['Bolivia','+591','BO'],['Bosnia and Herzegovina','+387','BA'],
  ['Botswana','+267','BW'],['Brazil','+55','BR'],['Brunei','+673','BN'],['Bulgaria','+359','BG'],
  ['Burkina Faso','+226','BF'],['Burundi','+257','BI'],['Cambodia','+855','KH'],['Cameroon','+237','CM'],
  ['Canada','+1','CA'],['Cape Verde','+238','CV'],['Cayman Islands','+1','KY'],
  ['Central African Republic','+236','CF'],['Chad','+235','TD'],['Chile','+56','CL'],['China','+86','CN'],
  ['Colombia','+57','CO'],['Comoros','+269','KM'],['Congo','+242','CG'],['Congo (DRC)','+243','CD'],
  ['Costa Rica','+506','CR'],['Côte d’Ivoire','+225','CI'],['Croatia','+385','HR'],['Cuba','+53','CU'],
  ['Curaçao','+599','CW'],['Cyprus','+357','CY'],['Czechia','+420','CZ'],['Denmark','+45','DK'],
  ['Djibouti','+253','DJ'],['Dominica','+1','DM'],['Dominican Republic','+1','DO'],['Ecuador','+593','EC'],
  ['Egypt','+20','EG'],['El Salvador','+503','SV'],['Equatorial Guinea','+240','GQ'],['Eritrea','+291','ER'],
  ['Estonia','+372','EE'],['Eswatini','+268','SZ'],['Ethiopia','+251','ET'],['Fiji','+679','FJ'],
  ['Finland','+358','FI'],['France','+33','FR'],['French Guiana','+594','GF'],['French Polynesia','+689','PF'],
  ['Gabon','+241','GA'],['Gambia','+220','GM'],['Georgia','+995','GE'],['Germany','+49','DE'],
  ['Ghana','+233','GH'],['Gibraltar','+350','GI'],['Greece','+30','GR'],['Greenland','+299','GL'],
  ['Grenada','+1','GD'],['Guadeloupe','+590','GP'],['Guam','+1','GU'],['Guatemala','+502','GT'],
  ['Guinea','+224','GN'],['Guinea-Bissau','+245','GW'],['Guyana','+592','GY'],['Haiti','+509','HT'],
  ['Honduras','+504','HN'],['Hong Kong','+852','HK'],['Hungary','+36','HU'],['Iceland','+354','IS'],
  ['India','+91','IN'],['Indonesia','+62','ID'],['Iran','+98','IR'],['Iraq','+964','IQ'],['Ireland','+353','IE'],
  ['Israel','+972','IL'],['Italy','+39','IT'],['Jamaica','+1','JM'],['Japan','+81','JP'],['Jordan','+962','JO'],
  ['Kazakhstan','+7','KZ'],['Kenya','+254','KE'],['Kiribati','+686','KI'],['Kosovo','+383','XK'],
  ['Kuwait','+965','KW'],['Kyrgyzstan','+996','KG'],['Laos','+856','LA'],['Latvia','+371','LV'],
  ['Lebanon','+961','LB'],['Lesotho','+266','LS'],['Liberia','+231','LR'],['Libya','+218','LY'],
  ['Liechtenstein','+423','LI'],['Lithuania','+370','LT'],['Luxembourg','+352','LU'],['Macau','+853','MO'],
  ['Madagascar','+261','MG'],['Malawi','+265','MW'],['Malaysia','+60','MY'],['Maldives','+960','MV'],
  ['Mali','+223','ML'],['Malta','+356','MT'],['Marshall Islands','+692','MH'],['Martinique','+596','MQ'],
  ['Mauritania','+222','MR'],['Mauritius','+230','MU'],['Mexico','+52','MX'],['Micronesia','+691','FM'],
  ['Moldova','+373','MD'],['Monaco','+377','MC'],['Mongolia','+976','MN'],['Montenegro','+382','ME'],
  ['Morocco','+212','MA'],['Mozambique','+258','MZ'],['Myanmar','+95','MM'],['Namibia','+264','NA'],
  ['Nauru','+674','NR'],['Nepal','+977','NP'],['Netherlands','+31','NL'],['New Caledonia','+687','NC'],
  ['New Zealand','+64','NZ'],['Nicaragua','+505','NI'],['Niger','+227','NE'],['Nigeria','+234','NG'],
  ['North Korea','+850','KP'],['North Macedonia','+389','MK'],['Norway','+47','NO'],['Oman','+968','OM'],
  ['Pakistan','+92','PK'],['Palau','+680','PW'],['Palestine','+970','PS'],['Panama','+507','PA'],
  ['Papua New Guinea','+675','PG'],['Paraguay','+595','PY'],['Peru','+51','PE'],['Philippines','+63','PH'],
  ['Poland','+48','PL'],['Portugal','+351','PT'],['Puerto Rico','+1','PR'],['Qatar','+974','QA'],
  ['Réunion','+262','RE'],['Romania','+40','RO'],['Russia','+7','RU'],['Rwanda','+250','RW'],
  ['Samoa','+685','WS'],['San Marino','+378','SM'],['São Tomé and Príncipe','+239','ST'],
  ['Saudi Arabia','+966','SA'],['Senegal','+221','SN'],['Serbia','+381','RS'],['Seychelles','+248','SC'],
  ['Sierra Leone','+232','SL'],['Singapore','+65','SG'],['Sint Maarten','+1','SX'],['Slovakia','+421','SK'],
  ['Slovenia','+386','SI'],['Solomon Islands','+677','SB'],['Somalia','+252','SO'],['South Africa','+27','ZA'],
  ['South Korea','+82','KR'],['South Sudan','+211','SS'],['Spain','+34','ES'],['Sri Lanka','+94','LK'],
  ['Sudan','+249','SD'],['Suriname','+597','SR'],['Sweden','+46','SE'],['Switzerland','+41','CH'],
  ['Syria','+963','SY'],['Taiwan','+886','TW'],['Tajikistan','+992','TJ'],['Tanzania','+255','TZ'],
  ['Thailand','+66','TH'],['Timor-Leste','+670','TL'],['Togo','+228','TG'],['Tonga','+676','TO'],
  ['Trinidad and Tobago','+1','TT'],['Tunisia','+216','TN'],['Türkiye','+90','TR'],
  ['Turkmenistan','+993','TM'],['Tuvalu','+688','TV'],['Uganda','+256','UG'],['Ukraine','+380','UA'],
  ['United Arab Emirates','+971','AE'],['United Kingdom','+44','GB'],['United States','+1','US'],
  ['Uruguay','+598','UY'],['Uzbekistan','+998','UZ'],['Vanuatu','+678','VU'],['Vatican City','+39','VA'],
  ['Venezuela','+58','VE'],['Vietnam','+84','VN'],['Yemen','+967','YE'],['Zambia','+260','ZM'],
  ['Zimbabwe','+263','ZW']
];

// The country name goes in the OPTGROUP, not the option. A native select shows
// group labels in the open list and never in the closed control, so the list is
// still browsable by country while the box itself stays at "+49 DE" wide.
export function dialOptions(priority = ['United States', 'United Kingdom', 'Canada', 'Australia']) {
  const fmt = ([name, code, iso]) => ({ value: code, label: `${code} ${iso}`, name });
  const top = priority.map((n) => DIAL_CODES.find((c) => c[0] === n)).filter(Boolean);
  const rest = DIAL_CODES.filter((c) => !priority.includes(c[0]));
  return { top: top.map(fmt), rest: rest.map(fmt) };
}