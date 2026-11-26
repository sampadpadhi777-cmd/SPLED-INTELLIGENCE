"""Supplemental Binance USD-M metrics with bounded, rotating all-symbol OI polling."""
import asyncio, logging
import httpx
from .config import settings
log=logging.getLogger(__name__)
class DerivativesPoller:
    def __init__(self,scanner,publish): self.scanner=scanner; self.publish=publish
    async def run(self):
        delay=2
        async with httpx.AsyncClient(timeout=12) as c:
            while True:
                try:
                    r=await c.get(settings.rest_base+'/fapi/v1/premiumIndex'); r.raise_for_status()
                    rows=r.json()
                    for row in rows if isinstance(rows,list) else [rows]:
                        state=self.scanner.market.get(row.get('symbol'))
                        if state:
                            state.mark_price=_f(row.get('markPrice')); state.index_price=_f(row.get('indexPrice'))
                            state.funding_rate=_f(row.get('lastFundingRate')); state.next_funding_time_ms=_i(row.get('nextFundingTime'))
                    # Rotate across the complete discovered market, rather than permanently
                    # excluding smaller contracts. One OI request per symbol per cycle.
                    symbols=sorted(set(self.scanner.market))
                    sem=asyncio.Semaphore(8)
                    async def get_oi(symbol):
                        async with sem:
                            try:
                                rr=await c.get(settings.rest_base+'/fapi/v1/openInterest',params={'symbol':symbol})
                                rr.raise_for_status(); state=self.scanner.market.get(symbol)
                                if state: state.open_interest=_f(rr.json().get('openInterest'))
                            except Exception as ex: log.debug('OI %s unavailable: %s',symbol,ex)
                    await asyncio.gather(*(get_oi(x) for x in symbols))
                    for symbol in symbols:
                        state=self.scanner.market.get(symbol)
                        if state: await self.publish({'type':'market_update','market':state.model_dump()})
                    delay=60
                except asyncio.CancelledError: raise
                except Exception as ex:
                    log.warning('derivatives poll failed: %s',ex); delay=min(max(delay*2,5),120)
                await asyncio.sleep(delay)
def _f(x):
    try: return float(x)
    except (TypeError,ValueError): return None
def _i(x):
    try: return int(x)
    except (TypeError,ValueError): return None
