using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    public class InspectionRecord
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("employee_id")]
        public string EmployeeId { get; set; } = string.Empty;

        // 兼容旧接口
        [Column("device_name")]
        public string? DeviceName { get; set; }

        [Column("device_model")]
        public string DeviceModel { get; set; } = string.Empty;

        [Column("inspection_time")]
        public DateTime InspectionTime { get; set; }

        // 兼容旧接口
        [Column("results_json")]
        public string? ResultsJson { get; set; }

        [Column("status")]
        public string Status { get; set; } = "submitted";

        [Column("frequency")]
        public string Frequency { get; set; } = "日";

        [Column("period_key")]
        public string PeriodKey { get; set; } = "";

    }
   
}
